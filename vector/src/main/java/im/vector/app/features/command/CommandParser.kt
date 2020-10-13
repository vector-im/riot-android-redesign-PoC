/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.command

import im.vector.app.core.extensions.isEmail
import im.vector.app.core.extensions.isMsisdn
import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.session.identity.ThreePid
import timber.log.Timber

object CommandParser {

    /**
     * Convert the text message into a Slash command.
     *
     * @param textMessage   the text message
     * @return a parsed slash command (ok or error)
     */
    fun parseSplashCommand(textMessage: CharSequence): ParsedCommand {
        // check if it has the Slash marker
        if (!textMessage.startsWith("/")) {
            return ParsedCommand.ErrorNotACommand
        } else {
            Timber.v("parseSplashCommand")

            // "/" only
            if (textMessage.length == 1) {
                return ParsedCommand.ErrorEmptySlashCommand
            }

            // Exclude "//"
            if ("/" == textMessage.substring(1, 2)) {
                return ParsedCommand.ErrorNotACommand
            }

            val messageParts = try {
                textMessage.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }
            } catch (e: Exception) {
                Timber.e(e, "## manageSplashCommand() : split failed")
                null
            }

            // test if the string cut fails
            if (messageParts.isNullOrEmpty()) {
                return ParsedCommand.ErrorEmptySlashCommand
            }

            return when (val slashCommand = messageParts.first()) {
                Command.PLAIN.command                        -> {
                    val text = textMessage.substring(Command.PLAIN.command.length).trim()

                    if (text.isNotEmpty()) {
                        ParsedCommand.SendPlainText(text)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.PLAIN)
                    }
                }
                Command.CHANGE_DISPLAY_NAME.command          -> {
                    val newDisplayName = textMessage.substring(Command.CHANGE_DISPLAY_NAME.command.length).trim()

                    if (newDisplayName.isNotEmpty()) {
                        ParsedCommand.ChangeDisplayName(newDisplayName)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.CHANGE_DISPLAY_NAME)
                    }
                }
                Command.CHANGE_DISPLAY_NAME_FOR_ROOM.command -> {
                    val newDisplayName = textMessage.substring(Command.CHANGE_DISPLAY_NAME_FOR_ROOM.command.length).trim()

                    if (newDisplayName.isNotEmpty()) {
                        ParsedCommand.ChangeDisplayNameForRoom(newDisplayName)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.CHANGE_DISPLAY_NAME_FOR_ROOM)
                    }
                }
                Command.ROOM_AVATAR.command                  -> {
                    if (messageParts.size == 2) {
                        val url = messageParts[1]

                        if (url.startsWith("mxc://")) {
                            ParsedCommand.ChangeRoomAvatar(url)
                        } else {
                            ParsedCommand.ErrorSyntax(Command.ROOM_AVATAR)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.ROOM_AVATAR)
                    }
                }
                Command.CHANGE_AVATAR_FOR_ROOM.command       -> {
                    if (messageParts.size == 2) {
                        val url = messageParts[1]

                        if (url.startsWith("mxc://")) {
                            ParsedCommand.ChangeAvatarForRoom(url)
                        } else {
                            ParsedCommand.ErrorSyntax(Command.CHANGE_AVATAR_FOR_ROOM)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.CHANGE_AVATAR_FOR_ROOM)
                    }
                }
                Command.TOPIC.command                        -> {
                    val newTopic = textMessage.substring(Command.TOPIC.command.length).trim()

                    if (newTopic.isNotEmpty()) {
                        ParsedCommand.ChangeTopic(newTopic)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.TOPIC)
                    }
                }
                Command.EMOTE.command                        -> {
                    val message = textMessage.subSequence(Command.EMOTE.command.length, textMessage.length).trim()

                    ParsedCommand.SendEmote(message)
                }
                Command.RAINBOW.command                      -> {
                    val message = textMessage.subSequence(Command.RAINBOW.command.length, textMessage.length).trim()

                    ParsedCommand.SendRainbow(message)
                }
                Command.RAINBOW_EMOTE.command                -> {
                    val message = textMessage.subSequence(Command.RAINBOW_EMOTE.command.length, textMessage.length).trim()

                    ParsedCommand.SendRainbowEmote(message)
                }
                Command.JOIN_ROOM.command                    -> {
                    if (messageParts.size >= 2) {
                        val roomAlias = messageParts[1]

                        if (roomAlias.isNotEmpty()) {
                            ParsedCommand.JoinRoom(
                                    roomAlias,
                                    textMessage.substring(Command.JOIN_ROOM.length + roomAlias.length)
                                            .trim()
                                            .takeIf { it.isNotBlank() }
                            )
                        } else {
                            ParsedCommand.ErrorSyntax(Command.JOIN_ROOM)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.JOIN_ROOM)
                    }
                }
                Command.PART.command                         -> {
                    if (messageParts.size >= 2) {
                        val roomAlias = messageParts[1]

                        if (roomAlias.isNotEmpty()) {
                            ParsedCommand.PartRoom(
                                    roomAlias,
                                    textMessage.substring(Command.PART.length + roomAlias.length)
                                            .trim()
                                            .takeIf { it.isNotBlank() }
                            )
                        } else {
                            ParsedCommand.ErrorSyntax(Command.PART)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.PART)
                    }
                }
                Command.ROOM_NAME.command                    -> {
                    val newRoomName = textMessage.substring(Command.ROOM_NAME.command.length).trim()

                    if (newRoomName.isNotEmpty()) {
                        ParsedCommand.ChangeRoomName(newRoomName)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.ROOM_NAME)
                    }
                }
                Command.INVITE.command                       -> {
                    if (messageParts.size >= 2) {
                        val userId = messageParts[1]

                        when {
                            MatrixPatterns.isUserId(userId) -> {
                                ParsedCommand.Invite(
                                        userId,
                                        textMessage.substring(Command.INVITE.length + userId.length)
                                                .trim()
                                                .takeIf { it.isNotBlank() }
                                )
                            }
                            userId.isEmail()                -> {
                                ParsedCommand.Invite3Pid(ThreePid.Email(userId))
                            }
                            userId.isMsisdn()               -> {
                                ParsedCommand.Invite3Pid(ThreePid.Msisdn(userId))
                            }
                            else                            -> {
                                ParsedCommand.ErrorSyntax(Command.INVITE)
                            }
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.INVITE)
                    }
                }
                Command.KICK_USER.command                    -> {
                    if (messageParts.size >= 2) {
                        val userId = messageParts[1]

                        if (MatrixPatterns.isUserId(userId)) {
                            ParsedCommand.KickUser(
                                    userId,
                                    textMessage.substring(Command.KICK_USER.length + userId.length)
                                            .trim()
                                            .takeIf { it.isNotBlank() }
                            )
                        } else {
                            ParsedCommand.ErrorSyntax(Command.KICK_USER)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.KICK_USER)
                    }
                }
                Command.BAN_USER.command                     -> {
                    if (messageParts.size >= 2) {
                        val userId = messageParts[1]

                        if (MatrixPatterns.isUserId(userId)) {
                            ParsedCommand.BanUser(
                                    userId,
                                    textMessage.substring(Command.BAN_USER.length + userId.length)
                                            .trim()
                                            .takeIf { it.isNotBlank() }
                            )
                        } else {
                            ParsedCommand.ErrorSyntax(Command.BAN_USER)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.BAN_USER)
                    }
                }
                Command.UNBAN_USER.command                   -> {
                    if (messageParts.size >= 2) {
                        val userId = messageParts[1]

                        if (MatrixPatterns.isUserId(userId)) {
                            ParsedCommand.UnbanUser(
                                    userId,
                                    textMessage.substring(Command.UNBAN_USER.length + userId.length)
                                            .trim()
                                            .takeIf { it.isNotBlank() }
                            )
                        } else {
                            ParsedCommand.ErrorSyntax(Command.UNBAN_USER)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.UNBAN_USER)
                    }
                }
                Command.IGNORE_USER.command                  -> {
                    if (messageParts.size == 2) {
                        val userId = messageParts[1]

                        if (MatrixPatterns.isUserId(userId)) {
                            ParsedCommand.IgnoreUser(userId)
                        } else {
                            ParsedCommand.ErrorSyntax(Command.IGNORE_USER)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.IGNORE_USER)
                    }
                }
                Command.UNIGNORE_USER.command                -> {
                    if (messageParts.size == 2) {
                        val userId = messageParts[1]

                        if (MatrixPatterns.isUserId(userId)) {
                            ParsedCommand.UnignoreUser(userId)
                        } else {
                            ParsedCommand.ErrorSyntax(Command.UNIGNORE_USER)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.UNIGNORE_USER)
                    }
                }
                Command.SET_USER_POWER_LEVEL.command         -> {
                    if (messageParts.size == 3) {
                        val userId = messageParts[1]
                        if (MatrixPatterns.isUserId(userId)) {
                            val powerLevelsAsString = messageParts[2]

                            try {
                                val powerLevelsAsInt = Integer.parseInt(powerLevelsAsString)

                                ParsedCommand.SetUserPowerLevel(userId, powerLevelsAsInt)
                            } catch (e: Exception) {
                                ParsedCommand.ErrorSyntax(Command.SET_USER_POWER_LEVEL)
                            }
                        } else {
                            ParsedCommand.ErrorSyntax(Command.SET_USER_POWER_LEVEL)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.SET_USER_POWER_LEVEL)
                    }
                }
                Command.RESET_USER_POWER_LEVEL.command       -> {
                    if (messageParts.size == 2) {
                        val userId = messageParts[1]

                        if (MatrixPatterns.isUserId(userId)) {
                            ParsedCommand.SetUserPowerLevel(userId, null)
                        } else {
                            ParsedCommand.ErrorSyntax(Command.SET_USER_POWER_LEVEL)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.SET_USER_POWER_LEVEL)
                    }
                }
                Command.MARKDOWN.command                     -> {
                    if (messageParts.size == 2) {
                        when {
                            "on".equals(messageParts[1], true)  -> ParsedCommand.SetMarkdown(true)
                            "off".equals(messageParts[1], true) -> ParsedCommand.SetMarkdown(false)
                            else                                -> ParsedCommand.ErrorSyntax(Command.MARKDOWN)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.MARKDOWN)
                    }
                }
                Command.CLEAR_SCALAR_TOKEN.command           -> {
                    if (messageParts.size == 1) {
                        ParsedCommand.ClearScalarToken
                    } else {
                        ParsedCommand.ErrorSyntax(Command.CLEAR_SCALAR_TOKEN)
                    }
                }
                Command.SPOILER.command                      -> {
                    val message = textMessage.substring(Command.SPOILER.command.length).trim()
                    ParsedCommand.SendSpoiler(message)
                }
                Command.SHRUG.command                        -> {
                    val message = textMessage.substring(Command.SHRUG.command.length).trim()

                    ParsedCommand.SendShrug(message)
                }
                Command.LENNY.command                        -> {
                    val message = textMessage.substring(Command.LENNY.command.length).trim()

                    ParsedCommand.SendLenny(message)
                }
                Command.POLL.command                         -> {
                    val rawCommand = textMessage.substring(Command.POLL.command.length).trim()
                    val split = rawCommand.split("|").map { it.trim() }
                    if (split.size > 2) {
                        ParsedCommand.SendPoll(split[0], split.subList(1, split.size))
                    } else {
                        ParsedCommand.ErrorSyntax(Command.POLL)
                    }
                }
                Command.DISCARD_SESSION.command              -> {
                    ParsedCommand.DiscardSession
                }
                Command.WHOIS.command                        -> {
                    if (messageParts.size == 2) {
                        val userId = messageParts[1]

                        if (MatrixPatterns.isUserId(userId)) {
                            ParsedCommand.ShowUser(userId)
                        } else {
                            ParsedCommand.ErrorSyntax(Command.WHOIS)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.WHOIS)
                    }
                }
                else                                         -> {
                    // Unknown command
                    ParsedCommand.ErrorUnknownSlashCommand(slashCommand)
                }
            }
        }
    }
}
