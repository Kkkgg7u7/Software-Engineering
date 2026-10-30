import type { PublicUser } from './social'
export interface Post {id:number;author:PublicUser;content:string;mediaIds:string[];likeCount:number;commentCount:number;liked:boolean;createdAt:string}
export interface PostComment {id:number;postId:number;author:PublicUser;content:string;createdAt:string}
export interface Feed<T>{items:T[];nextBeforeId:number|null;hasMore:boolean}
