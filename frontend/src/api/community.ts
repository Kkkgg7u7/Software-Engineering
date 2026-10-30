import { request } from './request'
import type { Post, PostComment, Feed } from '@/types/community'
export const communityApi={
 feed:(authorId?:number,beforeId?:number)=>request<Feed<Post>>({url:'/posts',params:{authorId,beforeId,limit:12}}),
 get:(id:number)=>request<Post>({url:`/posts/${id}`}),
 create:(data:{clientRequestId:string;content:string;mediaIds:string[]})=>request<Post>({url:'/posts',method:'POST',data}),
 remove:(id:number)=>request<void>({url:`/posts/${id}`,method:'DELETE'}),
 like:(id:number,on:boolean)=>request<Post>({url:`/posts/${id}/like`,method:on?'PUT':'DELETE'}),
 comments:(id:number,beforeId?:number)=>request<Feed<PostComment>>({url:`/posts/${id}/comments`,params:{beforeId,limit:20}}),
 comment:(id:number,data:{clientRequestId:string;content:string})=>request<PostComment>({url:`/posts/${id}/comments`,method:'POST',data}),
 removeComment:(postId:number,id:number)=>request<void>({url:`/posts/${postId}/comments/${id}`,method:'DELETE'})
}
