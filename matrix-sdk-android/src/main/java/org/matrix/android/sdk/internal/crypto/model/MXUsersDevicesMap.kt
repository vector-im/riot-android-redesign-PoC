/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.crypto.model

class MXUsersDevicesMap<E> {

    // A map of maps (userId -> (deviceId -> Object)).
    val map = HashMap<String /* userId */, HashMap<String /* deviceId */, E>>()

    /**
     * @return the user Ids
     */
    val userIds: List<String>
        get() = map.keys.toList()

    val isEmpty: Boolean
        get() = map.isEmpty()

    /**
     * Provides the device ids list for a user id
     * FIXME Should maybe return emptyList and not null, to avoid many !! in the code
     *
     * @param userId the user id
     * @return the device ids list
     */
    fun getUserDeviceIds(userId: String?): List<String>? {
        return if (!userId.isNullOrBlank() && map.containsKey(userId)) {
            map[userId]!!.keys.toList()
        } else null
    }

    /**
     * Provides the object for a device id and a user Id
     *
     * @param deviceId the device id
     * @param userId   the object id
     * @return the object
     */
    fun getObject(userId: String?, deviceId: String?): E? {
        return if (!userId.isNullOrBlank() && !deviceId.isNullOrBlank()) {
            map[userId]?.get(deviceId)
        } else null
    }

    /**
     * Set an object for a dedicated user Id and device Id
     *
     * @param userId   the user Id
     * @param deviceId the device id
     * @param o        the object to set
     */
    fun setObject(userId: String?, deviceId: String?, o: E?) {
        if (null != o && userId?.isNotBlank() == true && deviceId?.isNotBlank() == true) {
            val devices = map.getOrPut(userId) { HashMap() }
            devices[deviceId] = o
        }
    }

    /**
     * Defines the objects map for a user Id
     *
     * @param objectsPerDevices the objects maps
     * @param userId            the user id
     */
    fun setObjects(userId: String?, objectsPerDevices: Map<String, E>?) {
        if (!userId.isNullOrBlank()) {
            if (null == objectsPerDevices) {
                map.remove(userId)
            } else {
                map[userId] = HashMap(objectsPerDevices)
            }
        }
    }

    /**
     * Removes objects for a dedicated user
     *
     * @param userId the user id.
     */
    fun removeUserObjects(userId: String?) {
        if (!userId.isNullOrBlank()) {
            map.remove(userId)
        }
    }

    /**
     * Clear the internal dictionary
     */
    fun removeAllObjects() {
        map.clear()
    }

    /**
     * Add entries from another MXUsersDevicesMap
     *
     * @param other the other one
     */
    fun addEntriesFromMap(other: MXUsersDevicesMap<E>?) {
        if (null != other) {
            map.putAll(other.map)
        }
    }

    fun join(other: Map<out String, HashMap<String, E>>) {
        map.putAll(other)
    }

    override fun toString(): String {
        return "MXUsersDevicesMap $map"
    }
}

inline fun <T> MXUsersDevicesMap<T>.forEach(action: (String, String, T) -> Unit) {
    userIds.forEach { userId ->
        getUserDeviceIds(userId)?.forEach { deviceId ->
            getObject(userId, deviceId)?.let {
                action(userId, deviceId, it)
            }
        }
    }
}
