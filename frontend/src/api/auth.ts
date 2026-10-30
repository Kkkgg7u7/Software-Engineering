import { request } from './request'
import type { AuthResponse, LoginPayload, RegisterPayload, UserInfo } from '@/types'

/** 认证相关接口（成员 B 负责模块）。 */
export const authApi = {
  register(payload: RegisterPayload) {
    return request<AuthResponse>({ url: '/auth/register', method: 'POST', data: payload })
  },
  login(payload: LoginPayload) {
    return request<AuthResponse>({ url: '/auth/login', method: 'POST', data: payload })
  },
  logout() {
    return request<void>({ url: '/auth/logout', method: 'POST' })
  },
  me() {
    return request<UserInfo>({ url: '/users/me', method: 'GET' })
  },
  updateProfile(payload: { nickname?: string; avatar?: string; signature?: string }) {
    return request<UserInfo>({ url: '/users/me', method: 'PUT', data: payload })
  },
  changePassword(payload: { currentPassword: string; newPassword: string }) {
    return request<void>({ url: '/users/me/password', method: 'POST', data: payload })
  },
  health() {
    return request<Record<string, unknown>>({ url: '/health', method: 'GET' })
  }
}
