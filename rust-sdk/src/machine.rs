use std::{
    collections::{BTreeMap, HashMap},
    convert::{TryFrom, TryInto},
    io::Cursor,
};

use serde_json::{json, value::RawValue};
use tokio::runtime::Runtime;

use matrix_sdk_common::{
    api::r0::{
        keys::{
            claim_keys::Response as KeysClaimResponse, get_keys::Response as KeysQueryResponse,
            upload_keys::Response as KeysUploadResponse,
        },
        sync::sync_events::{DeviceLists as RumaDeviceLists, ToDevice},
        to_device::send_event_to_device::Response as ToDeviceResponse,
    },
    deserialized_responses::events::{AlgorithmInfo, SyncMessageEvent},
    events::{room::encrypted::EncryptedEventContent, AnyMessageEventContent, EventContent},
    identifiers::{DeviceKeyAlgorithm, RoomId, UserId},
    uuid::Uuid,
    IncomingResponse, UInt,
};

use matrix_sdk_crypto::{
    decrypt_key_export, encrypt_key_export, EncryptionSettings, OlmMachine as InnerMachine,
};

use crate::{
    error::{CryptoStoreError, DecryptionError, MachineCreationError},
    responses::{response_from_string, OwnedResponse},
    DecryptedEvent, Device, DeviceLists, KeyImportError, KeysImportResult, ProgressListener,
    Request, RequestType,
};

/// A high level state machine that handles E2EE for Matrix.
pub struct OlmMachine {
    inner: InnerMachine,
    runtime: Runtime,
}

pub struct Sas {
    pub other_user_id: String,
    pub other_device_id: String,
    pub flow_id: String,
    pub request: Request,
}

pub struct KeyRequestPair {
    pub cancellation: Option<Request>,
    pub key_request: Request,
}

impl OlmMachine {
    /// Create a new `OlmMachine`
    ///
    /// # Arguments
    ///
    /// * `user_id` - The unique ID of the user that owns this machine.
    ///
    /// * `device_id` - The unique ID of the device that owns this machine.
    ///
    /// * `path` - The path where the state of the machine should be persisted.
    pub fn new(user_id: &str, device_id: &str, path: &str) -> Result<Self, MachineCreationError> {
        let user_id = UserId::try_from(user_id)?;
        let device_id = device_id.into();
        let runtime = Runtime::new().unwrap();

        Ok(OlmMachine {
            inner: runtime.block_on(InnerMachine::new_with_default_store(
                &user_id, device_id, path, None,
            ))?,
            runtime,
        })
    }

    /// Get the user ID of the owner of this `OlmMachine`.
    pub fn user_id(&self) -> String {
        self.inner.user_id().to_string()
    }

    /// Get the device ID of the device of this `OlmMachine`.
    pub fn device_id(&self) -> String {
        self.inner.device_id().to_string()
    }

    /// Get a `Device` from the store.
    ///
    /// # Arguments
    ///
    /// * `user_id` - The id of the device owner.
    ///
    /// * `device_id` - The id of the device itself.
    pub fn get_device(
        &self,
        user_id: &str,
        device_id: &str,
    ) -> Result<Option<Device>, CryptoStoreError> {
        let user_id = UserId::try_from(user_id)?;

        Ok(self
            .runtime
            .block_on(self.inner.get_device(&user_id, device_id.into()))?
            .map(|d| d.into()))
    }

    /// Get all devices of an user.
    ///
    /// # Arguments
    ///
    /// * `user_id` - The id of the device owner.
    pub fn get_user_devices(&self, user_id: &str) -> Result<Vec<Device>, CryptoStoreError> {
        let user_id = UserId::try_from(user_id)?;

        Ok(self
            .runtime
            .block_on(self.inner.get_user_devices(&user_id))?
            .devices()
            .map(|d| d.into())
            .collect())
    }

    /// Get our own identity keys.
    pub fn identity_keys(&self) -> HashMap<String, String> {
        self.inner
            .identity_keys()
            .iter()
            .map(|(k, v)| (k.to_owned(), v.to_owned()))
            .collect()
    }

    /// Get the list of outgoing requests that need to be sent to the
    /// homeserver.
    ///
    /// After the request was sent out and a successful response was received
    /// the response body should be passed back to the state machine using the
    /// [mark_request_as_sent()](#method.mark_request_as_sent) method.
    ///
    /// **Note**: This method call should be locked per call.
    pub fn outgoing_requests(&self) -> Result<Vec<Request>, CryptoStoreError> {
        Ok(self
            .runtime
            .block_on(self.inner.outgoing_requests())?
            .into_iter()
            .map(|r| r.into())
            .collect())
    }

    /// Mark a request that was sent to the server as sent.
    ///
    /// # Arguments
    ///
    /// * `request_id` - The unique ID of the request that was sent out. This
    ///     needs to be an UUID.
    ///
    /// * `request_type` - The type of the request that was sent out.
    ///
    /// * `response_body` - The body of the response that was received.
    pub fn mark_request_as_sent(
        &self,
        request_id: &str,
        request_type: RequestType,
        response_body: &str,
    ) -> Result<(), CryptoStoreError> {
        let id = Uuid::parse_str(request_id).expect("Can't parse request id");

        let response = response_from_string(&response_body);

        let response: OwnedResponse = match request_type {
            RequestType::KeysUpload => {
                KeysUploadResponse::try_from_http_response(response).map(Into::into)
            }
            RequestType::KeysQuery => {
                KeysQueryResponse::try_from_http_response(response).map(Into::into)
            }
            RequestType::ToDevice => {
                ToDeviceResponse::try_from_http_response(response).map(Into::into)
            }
            RequestType::KeysClaim => {
                KeysClaimResponse::try_from_http_response(response).map(Into::into)
            }
        }
        .expect("Can't convert json string to response");

        self.runtime
            .block_on(self.inner.mark_request_as_sent(&id, &response))?;

        Ok(())
    }

    /// Let the state machine know about E2EE related sync changes that we
    /// received from the server.
    ///
    /// This needs to be called after every sync, ideally before processing
    /// any other sync changes.
    ///
    /// # Arguments
    ///
    /// * `events` - A serialized array of to-device events we received in the
    ///     current sync resposne.
    ///
    /// * `device_changes` - The list of devices that have changed in some way
    /// since the previous sync.
    ///
    /// * `key_counts` - The map of uploaded one-time key types and counts.
    pub fn receive_sync_changes(
        &self,
        events: &str,
        device_changes: DeviceLists,
        key_counts: HashMap<String, i32>,
    ) -> Result<String, CryptoStoreError> {
        let events: ToDevice = serde_json::from_str(events)?;
        let device_changes: RumaDeviceLists = device_changes.into();
        let key_counts: BTreeMap<DeviceKeyAlgorithm, UInt> = key_counts
            .into_iter()
            .map(|(k, v)| {
                (
                    DeviceKeyAlgorithm::from(k),
                    v.clamp(0, i32::MAX)
                        .try_into()
                        .expect("Couldn't convert key counts into an UInt"),
                )
            })
            .collect();

        let events = self
            .runtime
            .block_on(
                self.inner
                    .receive_sync_changes(&events, &device_changes, &key_counts),
            )
            .unwrap();

        Ok(serde_json::to_string(&events)?)
    }

    /// Add the given list of users to be tracked, triggering a key query request
    /// for them.
    ///
    /// *Note*: Only users that aren't already tracked will be considered for an
    /// update. It's safe to call this with already tracked users, it won't
    /// result in excessive keys query requests.
    ///
    /// # Arguments
    ///
    /// `users` - The users that should be queued up for a key query.
    pub fn update_tracked_users(&self, users: Vec<String>) {
        let users: Vec<UserId> = users
            .into_iter()
            .filter_map(|u| UserId::try_from(u).ok())
            .collect();

        self.runtime
            .block_on(self.inner.update_tracked_users(users.iter()));
    }

    /// Generate one-time key claiming requests for all the users we are missing
    /// sessions for.
    ///
    /// After the request was sent out and a successful response was received
    /// the response body should be passed back to the state machine using the
    /// [mark_request_as_sent()](#method.mark_request_as_sent) method.
    ///
    /// This method should be called every time before a call to
    /// [`share_group_session()`](#method.share_group_session) is made.
    ///
    /// # Arguments
    ///
    /// * `users` - The list of users for which we would like to establish 1:1
    /// Olm sessions for.
    pub fn get_missing_sessions(
        &self,
        users: Vec<String>,
    ) -> Result<Option<Request>, CryptoStoreError> {
        let users: Vec<UserId> = users
            .into_iter()
            .filter_map(|u| UserId::try_from(u).ok())
            .collect();

        Ok(self
            .runtime
            .block_on(self.inner.get_missing_sessions(users.iter()))?
            .map(|(request_id, request)| Request::KeysClaim {
                request_id: request_id.to_string(),
                one_time_keys: request
                    .one_time_keys
                    .into_iter()
                    .map(|(u, d)| {
                        (
                            u.to_string(),
                            d.into_iter()
                                .map(|(k, v)| (k.to_string(), v.to_string()))
                                .collect(),
                        )
                    })
                    .collect(),
            }))
    }

    /// Share a room key with the given list of users for the given room.
    ///
    /// After the request was sent out and a successful response was received
    /// the response body should be passed back to the state machine using the
    /// [mark_request_as_sent()](#method.mark_request_as_sent) method.
    ///
    /// This method should be called every time before a call to
    /// [`encrypt()`](#method.encrypt) with the given `room_id` is made.
    ///
    /// # Arguments
    ///
    /// * `room_id` - The unique id of the room, note that this doesn't strictly
    /// need to be a Matrix room, it just needs to be an unique identifier for
    /// the group that will participate in the conversation.
    ///
    /// * `users` - The list of users which are considered to be members of the
    /// room and should receive the room key.
    pub fn share_room_key(
        &self,
        room_id: &str,
        users: Vec<String>,
    ) -> Result<Vec<Request>, CryptoStoreError> {
        let users: Vec<UserId> = users
            .into_iter()
            .filter_map(|u| UserId::try_from(u).ok())
            .collect();

        let room_id = RoomId::try_from(room_id)?;
        let requests = self.runtime.block_on(self.inner.share_group_session(
            &room_id,
            users.iter(),
            EncryptionSettings::default(),
        ))?;

        Ok(requests.into_iter().map(|r| (&*r).into()).collect())
    }

    /// Encrypt the given event with the given type and content for the given
    /// room.
    ///
    /// **Note**: A room key needs to be shared with the group of users that are
    /// members in the given room. If this is not done this method will panic.
    ///
    /// The usual flow to encrypt an evnet using this state machine is as
    /// follows:
    ///
    /// 1. Get the one-time key claim request to establish 1:1 Olm sessions for
    ///    the room members of the room we wish to participate in. This is done
    ///    using the [`get_missing_sessions()`](#method.get_missing_sessions)
    ///    method. This method call should be locked per call.
    ///
    /// 2. Share a room key with all the room members using the
    ///    [`share_group_session()`](#method.share_group_session). This method
    ///    call should be locked per room.
    ///
    /// 3. Encrypt the event using this method.
    ///
    /// 4. Send the encrypted event to the server.
    ///
    /// After the room key is shared steps 1 and 2 will become noops, unless
    /// there's some changes in the room membership or in the list of devices a
    /// member has.
    ///
    /// # Arguments
    ///
    /// * `room_id` - The unique id of the room where the event will be sent to.
    ///
    /// * `even_type` - The type of the event.
    ///
    /// * `content` - The serialized content of the event.
    pub fn encrypt(
        &self,
        room_id: &str,
        event_type: &str,
        content: &str,
    ) -> Result<String, CryptoStoreError> {
        let room_id = RoomId::try_from(room_id)?;
        let content: Box<RawValue> = serde_json::from_str(content)?;

        let content = AnyMessageEventContent::from_parts(event_type, content)?;
        let encrypted_content = self
            .runtime
            .block_on(self.inner.encrypt(&room_id, content))
            .unwrap();

        Ok(serde_json::to_string(&encrypted_content)?)
    }

    /// Decrypt the given event that was sent in the given room.
    ///
    /// # Arguments
    ///
    /// * `event` - The serialized encrypted version of the event.
    ///
    /// * `room_id` - The unique id of the room where the event was sent to.
    pub fn decrypt_room_event(
        &self,
        event: &str,
        room_id: &str,
    ) -> Result<DecryptedEvent, DecryptionError> {
        let event: SyncMessageEvent<EncryptedEventContent> = serde_json::from_str(event)?;
        let room_id = RoomId::try_from(room_id)?;

        let decrypted = self
            .runtime
            .block_on(self.inner.decrypt_room_event(&event, &room_id))?;

        let encryption_info = decrypted
            .encryption_info()
            .expect("Decrypted event didn't contain any encryption info");

        let content = decrypted.content();

        let clear_event = json!({
            "type": content.event_type(),
            "content": content,
        });

        Ok(match &encryption_info.algorithm_info {
            AlgorithmInfo::MegolmV1AesSha2 {
                curve25519_key,
                sender_claimed_keys,
                forwarding_curve25519_key_chain,
            } => DecryptedEvent {
                clear_event: serde_json::to_string(&clear_event)?,
                sender_curve25519_key: curve25519_key.to_owned(),
                claimed_ed25519_key: sender_claimed_keys
                    .get(&DeviceKeyAlgorithm::Ed25519)
                    .cloned(),
                forwarding_curve25519_chain: forwarding_curve25519_key_chain.to_owned(),
            },
        })
    }

    /// Request or re-request a room key that was used to encrypt the given
    /// event.
    ///
    /// # Arguments
    ///
    /// * `event` - The undecryptable event that we would wish to request a room
    /// key for.
    ///
    /// * `room_id` - The id of the room the event was sent to.
    pub fn request_room_key(
        &self,
        event: &str,
        room_id: &str,
    ) -> Result<KeyRequestPair, DecryptionError> {
        let event: SyncMessageEvent<EncryptedEventContent> = serde_json::from_str(event)?;
        let room_id = RoomId::try_from(room_id)?;

        let (cancel, request) = self
            .runtime
            .block_on(self.inner.request_room_key(&event, &room_id))?;

        let cancellation = cancel.map(|r| r.into());
        let key_request = request.into();

        Ok(KeyRequestPair {
            cancellation,
            key_request,
        })
    }

    /// Export all of our room keys.
    ///
    /// # Arguments
    ///
    /// * `passphrase` - The passphrase that should be used to encrypt the key
    /// export.
    ///
    /// * `rounds` - The number of rounds that should be used when expanding the
    /// passphrase into an key.
    pub fn export_keys(&self, passphrase: &str, rounds: i32) -> Result<String, CryptoStoreError> {
        let keys = self.runtime.block_on(self.inner.export_keys(|_| true))?;

        let encrypted = encrypt_key_export(&keys, passphrase, rounds as u32)
            .map_err(CryptoStoreError::Serialization)?;

        Ok(encrypted)
    }

    /// Import room keys from the given serialized key export.
    ///
    /// # Arguments
    ///
    /// * `keys` - The serialized version of the key export.
    ///
    /// * `passphrase` - The passphrase that was used to encrypt the key export.
    ///
    /// * `progress_listener` - A callback that can be used to introspect the
    /// progress of the key import.
    pub fn import_keys(
        &self,
        keys: &str,
        passphrase: &str,
        progress_listener: Box<dyn ProgressListener>,
    ) -> Result<KeysImportResult, KeyImportError> {
        let keys = Cursor::new(keys);
        let keys = decrypt_key_export(keys, passphrase)?;

        let listener = |progress: usize, total: usize| {
            progress_listener.on_progress(progress as i32, total as i32)
        };

        let result = self
            .runtime
            .block_on(self.inner.import_keys(keys, listener))?;

        Ok(KeysImportResult {
            total: result.1 as i32,
            imported: result.0 as i32,
        })
    }

    /// Discard the currently active room key for the given room if there is
    /// one.
    pub fn discard_room_key(&self, room_id: &str) -> Result<(), CryptoStoreError> {
        let room_id = RoomId::try_from(room_id)?;

        self.runtime
            .block_on(self.inner.invalidate_group_session(&room_id))?;

        Ok(())
    }

    pub fn start_verification(&self, device: &Device) -> Result<Sas, CryptoStoreError> {
        let user_id = UserId::try_from(device.user_id.clone())?;
        let device_id = device.device_id.as_str().into();
        let device = self
            .runtime
            .block_on(self.inner.get_device(&user_id, device_id))?
            .unwrap();

        let (sas, request) = self.runtime.block_on(device.start_verification())?;

        Ok(Sas {
            other_user_id: sas.other_user_id().to_string(),
            other_device_id: sas.other_device_id().to_string(),
            flow_id: sas.flow_id().as_str().to_owned(),
            request: request.into(),
        })
    }
}
