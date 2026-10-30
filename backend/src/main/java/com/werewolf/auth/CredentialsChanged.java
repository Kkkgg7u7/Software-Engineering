package com.werewolf.auth;

/** Contains no credentials. Published inside the password-change transaction. */
public record CredentialsChanged(Long userId, int authVersion) { }
