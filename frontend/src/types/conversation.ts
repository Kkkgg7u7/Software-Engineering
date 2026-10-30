import type { PublicUser } from './social'
export interface ConversationMessage { id:number; conversationId:number; sender:PublicUser; clientMessageId:string; contentType:'TEXT'|'IMAGE'|'GROUP_INVITE'|'GAME_INVITE'; content:string|null; mediaId:string|null; relatedCode:string|null; createdAt:string }
export interface Conversation { id:number; type:'DIRECT'|'GROUP'; title:string; code:string|null; createdBy:number; memberCount:number; peer:PublicUser|null; lastMessage:ConversationMessage|null; unreadCount:number; updatedAt:string }
export interface History { items:ConversationMessage[]; nextBeforeId:number|null; hasMore:boolean }
export interface SendMessage { clientMessageId:string; contentType:'TEXT'|'IMAGE'; content?:string; mediaId?:string }
