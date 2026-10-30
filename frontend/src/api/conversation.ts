import { request } from './request'
import type { Conversation, ConversationMessage, History, SendMessage } from '@/types/conversation'
import type { PageResult, PublicUser } from '@/types/social'
export const conversationApi = {
 list:(page=1)=>request<PageResult<Conversation>>({url:'/conversations',params:{page,size:50}}),
 get:(id:number)=>request<Conversation>({url:`/conversations/${id}`}),
 direct:(targetUserId:number)=>request<Conversation>({url:'/conversations/direct',method:'POST',data:{targetUserId}}),
 history:(id:number,beforeId?:number)=>request<History>({url:`/conversations/${id}/messages`,params:{beforeId,limit:30}}),
 send:(id:number,data:SendMessage)=>request<ConversationMessage>({url:`/conversations/${id}/messages`,method:'POST',data}),
 read:(id:number,messageId:number)=>request<void>({url:`/conversations/${id}/read`,method:'POST',data:{messageId}}),
 create:(name:string)=>request<Conversation>({url:'/groups',method:'POST',data:{name}}),
 join:(code:string)=>request<Conversation>({url:'/groups/join',method:'POST',data:{code}}),
 members:(id:number)=>request<PublicUser[]>({url:`/groups/${id}/members`}),
 rename:(id:number,name:string)=>request<Conversation>({url:`/groups/${id}`,method:'PUT',data:{name}}),
 leave:(id:number)=>request<void>({url:`/groups/${id}/leave`,method:'POST'}),
 invite:(id:number,targetUserId:number,clientMessageId:string)=>request<ConversationMessage>({url:`/groups/${id}/invite`,method:'POST',data:{targetUserId,clientMessageId}})
}
