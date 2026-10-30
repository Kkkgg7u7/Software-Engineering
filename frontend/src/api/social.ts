import { request } from './request'
import type { FriendRequest, PageResult, PublicUser, ImageAsset } from '@/types/social'

export const socialApi = {
  search(q: string, page = 1) { return request<PageResult<PublicUser>>({ url: '/users/search', params: { q, page, size: 20 } }) },
  user(id: number) { return request<PublicUser>({ url: `/users/${id}` }) },
  friends(page = 1) { return request<PageResult<PublicUser>>({ url: '/friends', params: { page, size: 20 } }) },
  requests(direction: 'incoming' | 'outgoing', page = 1) { return request<PageResult<FriendRequest>>({ url: '/friends/requests', params: { direction, page, size: 20 } }) },
  add(targetUserId: number) { return request<FriendRequest>({ url: '/friends/requests', method: 'POST', data: { targetUserId } }) },
  accept(id: number) { return request<FriendRequest>({ url: `/friends/requests/${id}/accept`, method: 'POST' }) },
  reject(id: number) { return request<FriendRequest>({ url: `/friends/requests/${id}/reject`, method: 'POST' }) },
  remove(id: number) { return request<void>({ url: `/friends/${id}`, method: 'DELETE' }) }
}
export const mediaApi = {
  upload(file: File, purpose: 'PRIVATE' | 'AVATAR' = 'PRIVATE') {
    const data = new FormData()
    data.append('file', file)
    return request<ImageAsset>({ url: '/media/images', method: 'POST', params: { purpose }, data, headers: { 'Content-Type': undefined }, timeout: 30000 })
  }
}
