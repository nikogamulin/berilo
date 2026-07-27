package app.berilo.reader.vault

import app.berilo.reader.settings.KeyValueStore

/** Preference key the vault passphrase is stored under. */
private const val VAULT_PASSPHRASE_KEY = "vault_passphrase"

/**
 * Where the user's own vault secret comes from (S3.7, `docs/sync_api.md` §8.2(2)).
 *
 * An interface for the same reason [app.berilo.reader.settings.KeyValueStore] is one: the
 * production implementation reads [EncryptedSharedPreferences][StoredVaultSecret], which needs a
 * live Android Keystore and Tink and therefore cannot run in a JVM unit test. Tests supply an
 * in-memory secret and exercise the same [VaultRepository].
 *
 * **The value this returns never reaches a [VaultGateway].** It is used only to derive a key
 * ([VaultCrypto.deriveKey]); what is transmitted is salt, nonce and ciphertext. `VaultCryptoTest`
 * and `VaultIsolationTest` assert that no transmitted or server-side byte contains it.
 */
fun interface VaultSecret {

    /**
     * The user's vault passphrase, or null when they have not set one — in which case the vault
     * is unavailable and [VaultRepository] refuses to upload rather than encrypting under a
     * default nobody chose.
     *
     * Returns a fresh array on each call; callers clear it when done.
     */
    fun passphrase(): CharArray?
}

/**
 * Production [VaultSecret], reading the passphrase from the same
 * [EncryptedSharedPreferences][app.berilo.reader.settings.EncryptedKeyValueStore]-backed store
 * that already holds the user's LLM API key (CLAUDE.md §2's BYO-key invariant).
 *
 * That store is the established precedent for "a secret that lives on this device and is never
 * logged, never committed and never transmitted", which is exactly the vault passphrase's
 * status. Like [app.berilo.reader.settings.EncryptedKeyValueStore] itself, this class is
 * verified on-device rather than by JVM tests; [VaultRepository] is tested against a fake.
 */
class StoredVaultSecret(private val store: KeyValueStore) : VaultSecret {
    override fun passphrase(): CharArray? =
        store.getString(VAULT_PASSPHRASE_KEY)?.takeIf { it.isNotEmpty() }?.toCharArray()
}
