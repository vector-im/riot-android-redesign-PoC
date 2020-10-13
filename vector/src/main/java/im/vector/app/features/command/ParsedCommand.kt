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

import org.matrix.android.sdk.api.session.identity.ThreePid

/**
 * Represent a parsed command
 */
sealed class ParsedCommand {
    // This is not a Slash command
    object ErrorNotACommand : ParsedCommand()

    object ErrorEmptySlashCommand : ParsedCommand()

    // Unknown/Unsupported slash command
    class ErrorUnknownSlashCommand(val slashCommand: String) : ParsedCommand()

    // A slash command is detected, but there is an error
    class ErrorSyntax(val command: Command) : ParsedCommand()

    // Valid commands:

    class SendPlainText(val message: CharSequence) : ParsedCommand()
    class SendEmote(val message: CharSequence) : ParsedCommand()
    class SendRainbow(val message: CharSequence) : ParsedCommand()
    class SendRainbowEmote(val message: CharSequence) : ParsedCommand()
    class BanUser(val userId: String, val reason: String?) : ParsedCommand()
    class UnbanUser(val userId: String, val reason: String?) : ParsedCommand()
    class IgnoreUser(val userId: String) : ParsedCommand()
    class UnignoreUser(val userId: String) : ParsedCommand()
    class SetUserPowerLevel(val userId: String, val powerLevel: Int?) : ParsedCommand()
    class ChangeRoomName(val name: String) : ParsedCommand()
    class Invite(val userId: String, val reason: String?) : ParsedCommand()
    class Invite3Pid(val threePid: ThreePid) : ParsedCommand()
    class JoinRoom(val roomAlias: String, val reason: String?) : ParsedCommand()
    class PartRoom(val roomAlias: String, val reason: String?) : ParsedCommand()
    class ChangeTopic(val topic: String) : ParsedCommand()
    class KickUser(val userId: String, val reason: String?) : ParsedCommand()
    class ChangeDisplayName(val displayName: String) : ParsedCommand()
    class ChangeDisplayNameForRoom(val displayName: String) : ParsedCommand()
    class ChangeRoomAvatar(val url: String) : ParsedCommand()
    class ChangeAvatarForRoom(val url: String) : ParsedCommand()
    class SetMarkdown(val enable: Boolean) : ParsedCommand()
    object ClearScalarToken : ParsedCommand()
    class SendSpoiler(val message: String) : ParsedCommand()
    class SendShrug(val message: CharSequence) : ParsedCommand()
    class SendLenny(val message: CharSequence) : ParsedCommand()
    class SendPoll(val question: String, val options: List<String>) : ParsedCommand()
    object DiscardSession : ParsedCommand()
    class ShowUser(val userId: String) : ParsedCommand()
}
