export interface PageResult<T> { items: T[]; total: number; page: number; size: number }
export interface PublicUser { id: number; nickname: string; avatar?: string; signature?: string; online: boolean; totalGames: number; winGames: number }
export interface FriendRequest { id: number; requester: PublicUser; recipient: PublicUser; status: 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'DELETED'; createdAt: string }
export interface ImageAsset { id: string; url: string; purpose: 'PRIVATE' | 'AVATAR'; width: number; height: number; sizeBytes: number; contentType: string }
