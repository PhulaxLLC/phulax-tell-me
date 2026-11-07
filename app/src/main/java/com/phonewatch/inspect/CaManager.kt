package com.phonewatch.inspect

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.ContentSigner
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * PhoneWatch's own root CA for Deep Inspect, made on this phone.
 *
 * The CA private key is generated INSIDE the Android Keystore (hardware backed where the phone has it)
 * and is marked non-exportable: PhoneWatch can ask the Keystore to sign with it, but the key itself can
 * never be read, copied, logged or sent anywhere. Only the public certificate is ever written out.
 */
object CaManager {
    private const val TAG = "PW.Ca"
    private const val ALIAS = "phonewatch_deep_inspect_ca"
    private const val CERT_FILE = "deep/ca.der"
    const val CA_NAME = "Phulax Android Tell Me Deep Inspect CA (this phone only)"

    private lateinit var ctx: Context
    @Volatile private var caCert: X509Certificate? = null
    private val leafKeys: KeyPair by lazy {
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }
    private val contexts = object : LinkedHashMap<String, SSLContext>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SSLContext>?) = size > 400
    }

    fun init(c: Context) {
        ctx = c.applicationContext
        caCert = runCatching {
            val f = File(ctx.filesDir, CERT_FILE)
            if (f.exists() && keystore().containsAlias(ALIAS))
                CertificateFactory.getInstance("X.509").generateCertificate(f.inputStream()) as X509Certificate
            else null
        }.getOrNull()
    }

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun exists(): Boolean = caCert != null
    fun cert(): X509Certificate? = caCert
    fun fingerprint(): String? = caCert?.let {
        java.security.MessageDigest.getInstance("SHA-256").digest(it.encoded).joinToString(":") { b -> "%02X".format(b) }.take(47)
    }

    /** Is our CA in Android's user certificate store (the owner installed it in Settings)? */
    fun installedInAndroid(): Boolean {
        val mine = caCert ?: return false
        return runCatching {
            val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            ks.aliases().toList().filter { it.startsWith("user:") }.any { (ks.getCertificate(it) as? X509Certificate)?.encoded?.contentEquals(mine.encoded) == true }
        }.getOrDefault(false)
    }

    /** Creates the CA (key in the Keystore + self-signed certificate). */
    @Synchronized
    fun create(): X509Certificate {
        caCert?.let { return it }
        val ks = keystore()
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kpg.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        val kp = kpg.generateKeyPair()
        val name = X500Name("CN=$CA_NAME, O=Phulax Android Tell Me personal, OU=${Build.MODEL}")
        val now = System.currentTimeMillis()
        val serial = BigInteger(64, SecureRandom()).abs()
        val ext = JcaX509ExtensionUtils()
        val b = JcaX509v3CertificateBuilder(name, serial, Date(now - 86_400_000L), Date(now + 10L * 365 * 86_400_000L), name, kp.public)
            .addExtension(Extension.basicConstraints, true, BasicConstraints(0))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
            .addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(kp.public))
        val cert = JcaX509CertificateConverter().getCertificate(b.build(KeystoreSigner(kp.private)))
        File(ctx.filesDir, CERT_FILE).apply { parentFile?.mkdirs() }.writeBytes(cert.encoded)
        caCert = cert
        synchronized(contexts) { contexts.clear() }
        Log.i(TAG, "CA created")
        return cert
    }

    /** Deletes the CA key from the Keystore and the certificate file. Traffic can no longer be decrypted. */
    @Synchronized
    fun delete() {
        runCatching { keystore().deleteEntry(ALIAS) }
        File(ctx.filesDir, CERT_FILE).delete()
        // the exported public copy in Downloads is useless now; remove it so it isn't installed by mistake
        runCatching { ctx.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf("Phulax Android Tell Me-CA.crt")) }
        caCert = null
        synchronized(contexts) { contexts.clear() }
    }

    /** Writes ONLY the public certificate to Downloads. Returns the shown path. */
    fun exportPublicCert(): String? {
        val cert = caCert ?: return null
        val name = "Phulax Android Tell Me-CA.crt"
        return runCatching {
            val res = ctx.contentResolver
            // replace an older copy so the owner always installs the current certificate
            res.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name))
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
            }
            val uri = res.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
            res.openOutputStream(uri)?.use { it.write(cert.encoded) }
            "Downloads/$name"
        }.onFailure { Log.w(TAG, "export", it) }.getOrNull()
    }

    /** SSLContext presenting a certificate for [host] signed by our CA (cached per host). */
    fun serverContext(host: String): SSLContext {
        synchronized(contexts) { contexts[host]?.let { return it } }
        val ca = caCert ?: throw IllegalStateException("no CA")
        val leaf = mintLeaf(host, ca)
        val km = FixedKeyManager(leafKeys.private, arrayOf(leaf, ca))
        val sc = SSLContext.getInstance("TLS")
        sc.init(arrayOf<KeyManager>(km), null, null)
        synchronized(contexts) { contexts[host] = sc }
        return sc
    }

    private fun mintLeaf(host: String, ca: X509Certificate): X509Certificate {
        val key = KeyStoreKey.get(ALIAS) ?: throw IllegalStateException("CA key missing")
        val now = System.currentTimeMillis()
        val isIp = host.matches(Regex("[0-9.]+")) || host.contains(':')
        val san = GeneralNames(GeneralName(if (isIp) GeneralName.iPAddress else GeneralName.dNSName, host))
        val ext = JcaX509ExtensionUtils()
        val cn = if (host.length <= 64) host else host.takeLast(64)
        val b: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            X500Name.getInstance(ca.subjectX500Principal.encoded), BigInteger(64, SecureRandom()).abs(),
            Date(now - 86_400_000L), Date(now + 365L * 86_400_000L), X500Name("CN=$cn"), leafKeys.public)
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            .addExtension(Extension.subjectAlternativeName, false, san)
            .addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(ca))
            .addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(leafKeys.public))
        return JcaX509CertificateConverter().getCertificate(b.build(KeystoreSigner(key)))
    }

    private object KeyStoreKey {
        fun get(alias: String): PrivateKey? = runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as PrivateKey
        }.getOrNull()
    }

    /** BouncyCastle signer that asks the Android Keystore to sign; the key never leaves the Keystore. */
    private class KeystoreSigner(private val key: PrivateKey) : ContentSigner {
        private val buf = ByteArrayOutputStream()
        override fun getAlgorithmIdentifier() = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        override fun getOutputStream() = buf
        override fun getSignature(): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(key); update(buf.toByteArray()); sign()
        }
    }

    private class FixedKeyManager(private val key: PrivateKey, private val chain: Array<X509Certificate>) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("leaf")
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = "leaf"
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = "leaf"
        override fun getCertificateChain(alias: String?) = chain
        override fun getPrivateKey(alias: String?) = key
    }

}
