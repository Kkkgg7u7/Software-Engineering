import {request} from './request'
export interface GameMetadata{type:string;name:string;description:string;category:string;status:string;poster:string;recommendedPlayers:number[];minPlayers:number;maxPlayers:number;rules:string[];lobbyPath:string}
export const gamesApi={list:()=>request<GameMetadata[]>({url:'/games'})}
