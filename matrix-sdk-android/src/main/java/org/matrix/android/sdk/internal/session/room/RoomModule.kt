/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room

import dagger.Binds
import dagger.Module
import dagger.Provides
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.matrix.android.sdk.api.session.file.FileService
import org.matrix.android.sdk.api.session.room.RoomDirectoryService
import org.matrix.android.sdk.api.session.room.RoomService
import org.matrix.android.sdk.api.session.space.SpaceService
import org.matrix.android.sdk.internal.session.DefaultFileService
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.directory.DirectoryAPI
import org.matrix.android.sdk.internal.session.room.accountdata.DefaultUpdateRoomAccountDataTask
import org.matrix.android.sdk.internal.session.room.accountdata.UpdateRoomAccountDataTask
import org.matrix.android.sdk.internal.session.room.alias.AddRoomAliasTask
import org.matrix.android.sdk.internal.session.room.alias.DefaultAddRoomAliasTask
import org.matrix.android.sdk.internal.session.room.alias.DefaultDeleteRoomAliasTask
import org.matrix.android.sdk.internal.session.room.alias.DefaultGetRoomIdByAliasTask
import org.matrix.android.sdk.internal.session.room.alias.DefaultGetRoomLocalAliasesTask
import org.matrix.android.sdk.internal.session.room.alias.DeleteRoomAliasTask
import org.matrix.android.sdk.internal.session.room.alias.GetRoomIdByAliasTask
import org.matrix.android.sdk.internal.session.room.alias.GetRoomLocalAliasesTask
import org.matrix.android.sdk.internal.session.room.create.CreateRoomTask
import org.matrix.android.sdk.internal.session.room.create.DefaultCreateRoomTask
import org.matrix.android.sdk.internal.session.room.directory.DefaultGetPublicRoomTask
import org.matrix.android.sdk.internal.session.room.directory.DefaultGetRoomDirectoryVisibilityTask
import org.matrix.android.sdk.internal.session.room.directory.DefaultSetRoomDirectoryVisibilityTask
import org.matrix.android.sdk.internal.session.room.directory.GetPublicRoomTask
import org.matrix.android.sdk.internal.session.room.directory.GetRoomDirectoryVisibilityTask
import org.matrix.android.sdk.internal.session.room.directory.SetRoomDirectoryVisibilityTask
import org.matrix.android.sdk.internal.session.room.membership.DefaultLoadRoomMembersTask
import org.matrix.android.sdk.internal.session.room.membership.LoadRoomMembersTask
import org.matrix.android.sdk.internal.session.room.membership.admin.DefaultMembershipAdminTask
import org.matrix.android.sdk.internal.session.room.membership.admin.MembershipAdminTask
import org.matrix.android.sdk.internal.session.room.membership.joining.DefaultInviteTask
import org.matrix.android.sdk.internal.session.room.membership.joining.DefaultJoinRoomTask
import org.matrix.android.sdk.internal.session.room.membership.joining.InviteTask
import org.matrix.android.sdk.internal.session.room.membership.joining.JoinRoomTask
import org.matrix.android.sdk.internal.session.room.membership.leaving.DefaultLeaveRoomTask
import org.matrix.android.sdk.internal.session.room.membership.leaving.LeaveRoomTask
import org.matrix.android.sdk.internal.session.room.membership.threepid.DefaultInviteThreePidTask
import org.matrix.android.sdk.internal.session.room.membership.threepid.InviteThreePidTask
import org.matrix.android.sdk.internal.session.room.peeking.DefaultPeekRoomTask
import org.matrix.android.sdk.internal.session.room.peeking.DefaultResolveRoomStateTask
import org.matrix.android.sdk.internal.session.room.peeking.PeekRoomTask
import org.matrix.android.sdk.internal.session.room.peeking.ResolveRoomStateTask
import org.matrix.android.sdk.internal.session.room.read.DefaultMarkAllRoomsReadTask
import org.matrix.android.sdk.internal.session.room.read.DefaultSetReadMarkersTask
import org.matrix.android.sdk.internal.session.room.read.MarkAllRoomsReadTask
import org.matrix.android.sdk.internal.session.room.read.SetReadMarkersTask
import org.matrix.android.sdk.internal.session.room.relation.DefaultFetchEditHistoryTask
import org.matrix.android.sdk.internal.session.room.relation.DefaultFindReactionEventForUndoTask
import org.matrix.android.sdk.internal.session.room.relation.DefaultUpdateQuickReactionTask
import org.matrix.android.sdk.internal.session.room.relation.FetchEditHistoryTask
import org.matrix.android.sdk.internal.session.room.relation.FindReactionEventForUndoTask
import org.matrix.android.sdk.internal.session.room.relation.UpdateQuickReactionTask
import org.matrix.android.sdk.internal.session.room.reporting.DefaultReportContentTask
import org.matrix.android.sdk.internal.session.room.reporting.ReportContentTask
import org.matrix.android.sdk.internal.session.room.state.DefaultSendStateTask
import org.matrix.android.sdk.internal.session.room.state.SendStateTask
import org.matrix.android.sdk.internal.session.room.tags.AddTagToRoomTask
import org.matrix.android.sdk.internal.session.room.tags.DefaultAddTagToRoomTask
import org.matrix.android.sdk.internal.session.room.tags.DefaultDeleteTagFromRoomTask
import org.matrix.android.sdk.internal.session.room.tags.DeleteTagFromRoomTask
import org.matrix.android.sdk.internal.session.room.timeline.DefaultFetchTokenAndPaginateTask
import org.matrix.android.sdk.internal.session.room.timeline.DefaultGetContextOfEventTask
import org.matrix.android.sdk.internal.session.room.timeline.DefaultGetEventTask
import org.matrix.android.sdk.internal.session.room.timeline.DefaultPaginationTask
import org.matrix.android.sdk.internal.session.room.timeline.FetchTokenAndPaginateTask
import org.matrix.android.sdk.internal.session.room.timeline.GetContextOfEventTask
import org.matrix.android.sdk.internal.session.room.timeline.GetEventTask
import org.matrix.android.sdk.internal.session.room.timeline.PaginationTask
import org.matrix.android.sdk.internal.session.room.typing.DefaultSendTypingTask
import org.matrix.android.sdk.internal.session.room.typing.SendTypingTask
import org.matrix.android.sdk.internal.session.room.uploads.DefaultGetUploadsTask
import org.matrix.android.sdk.internal.session.room.uploads.GetUploadsTask
import org.matrix.android.sdk.internal.session.space.DefaultSpaceService
import retrofit2.Retrofit

@Module
internal abstract class RoomModule {

    @Module
    companion object {
        @Provides
        @JvmStatic
        @SessionScope
        fun providesRoomAPI(retrofit: Retrofit): RoomAPI {
            return retrofit.create(RoomAPI::class.java)
        }

        @Provides
        @JvmStatic
        @SessionScope
        fun providesDirectoryAPI(retrofit: Retrofit): DirectoryAPI {
            return retrofit.create(DirectoryAPI::class.java)
        }

        @Provides
        @JvmStatic
        fun providesParser(): Parser {
            return Parser.builder().build()
        }

        @Provides
        @JvmStatic
        fun providesHtmlRenderer(): HtmlRenderer {
            return HtmlRenderer
                    .builder()
                    .softbreak("<br />")
                    .build()
        }
    }

    @Binds
    abstract fun bindRoomFactory(factory: DefaultRoomFactory): RoomFactory

    @Binds
    abstract fun bindRoomGetter(getter: DefaultRoomGetter): RoomGetter

    @Binds
    abstract fun bindRoomService(service: DefaultRoomService): RoomService

    @Binds
    abstract fun bindSpaceService(service: DefaultSpaceService): SpaceService

    @Binds
    abstract fun bindRoomDirectoryService(service: DefaultRoomDirectoryService): RoomDirectoryService

    @Binds
    abstract fun bindFileService(service: DefaultFileService): FileService

    @Binds
    abstract fun bindCreateRoomTask(task: DefaultCreateRoomTask): CreateRoomTask

    @Binds
    abstract fun bindGetPublicRoomTask(task: DefaultGetPublicRoomTask): GetPublicRoomTask

    @Binds
    abstract fun bindGetRoomDirectoryVisibilityTask(task: DefaultGetRoomDirectoryVisibilityTask): GetRoomDirectoryVisibilityTask

    @Binds
    abstract fun bindSetRoomDirectoryVisibilityTask(task: DefaultSetRoomDirectoryVisibilityTask): SetRoomDirectoryVisibilityTask

    @Binds
    abstract fun bindInviteTask(task: DefaultInviteTask): InviteTask

    @Binds
    abstract fun bindInviteThreePidTask(task: DefaultInviteThreePidTask): InviteThreePidTask

    @Binds
    abstract fun bindJoinRoomTask(task: DefaultJoinRoomTask): JoinRoomTask

    @Binds
    abstract fun bindLeaveRoomTask(task: DefaultLeaveRoomTask): LeaveRoomTask

    @Binds
    abstract fun bindMembershipAdminTask(task: DefaultMembershipAdminTask): MembershipAdminTask

    @Binds
    abstract fun bindLoadRoomMembersTask(task: DefaultLoadRoomMembersTask): LoadRoomMembersTask

    @Binds
    abstract fun bindSetReadMarkersTask(task: DefaultSetReadMarkersTask): SetReadMarkersTask

    @Binds
    abstract fun bindMarkAllRoomsReadTask(task: DefaultMarkAllRoomsReadTask): MarkAllRoomsReadTask

    @Binds
    abstract fun bindFindReactionEventForUndoTask(task: DefaultFindReactionEventForUndoTask): FindReactionEventForUndoTask

    @Binds
    abstract fun bindUpdateQuickReactionTask(task: DefaultUpdateQuickReactionTask): UpdateQuickReactionTask

    @Binds
    abstract fun bindSendStateTask(task: DefaultSendStateTask): SendStateTask

    @Binds
    abstract fun bindReportContentTask(task: DefaultReportContentTask): ReportContentTask

    @Binds
    abstract fun bindGetContextOfEventTask(task: DefaultGetContextOfEventTask): GetContextOfEventTask

    @Binds
    abstract fun bindPaginationTask(task: DefaultPaginationTask): PaginationTask

    @Binds
    abstract fun bindFetchNextTokenAndPaginateTask(task: DefaultFetchTokenAndPaginateTask): FetchTokenAndPaginateTask

    @Binds
    abstract fun bindFetchEditHistoryTask(task: DefaultFetchEditHistoryTask): FetchEditHistoryTask

    @Binds
    abstract fun bindGetRoomIdByAliasTask(task: DefaultGetRoomIdByAliasTask): GetRoomIdByAliasTask

    @Binds
    abstract fun bindGetRoomLocalAliasesTask(task: DefaultGetRoomLocalAliasesTask): GetRoomLocalAliasesTask

    @Binds
    abstract fun bindAddRoomAliasTask(task: DefaultAddRoomAliasTask): AddRoomAliasTask

    @Binds
    abstract fun bindDeleteRoomAliasTask(task: DefaultDeleteRoomAliasTask): DeleteRoomAliasTask

    @Binds
    abstract fun bindSendTypingTask(task: DefaultSendTypingTask): SendTypingTask

    @Binds
    abstract fun bindGetUploadsTask(task: DefaultGetUploadsTask): GetUploadsTask

    @Binds
    abstract fun bindAddTagToRoomTask(task: DefaultAddTagToRoomTask): AddTagToRoomTask

    @Binds
    abstract fun bindDeleteTagFromRoomTask(task: DefaultDeleteTagFromRoomTask): DeleteTagFromRoomTask

    @Binds
    abstract fun bindResolveRoomStateTask(task: DefaultResolveRoomStateTask): ResolveRoomStateTask

    @Binds
    abstract fun bindPeekRoomTask(task: DefaultPeekRoomTask): PeekRoomTask

    @Binds
    abstract fun bindUpdateRoomAccountDataTask(task: DefaultUpdateRoomAccountDataTask): UpdateRoomAccountDataTask

    @Binds
    abstract fun bindGetEventTask(task: DefaultGetEventTask): GetEventTask
}
