package dev.arcdrift.intellij.ui

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent

internal data class AgentSettings(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val maxTurns: Int,
    val hasSavedApiKey: Boolean = apiKey.isNotBlank()
)

/**
 * Pure, testable persistence & resolution helper for Agent Workspace settings
 * backed by IntelliJ `PropertiesComponent` (non-secret fields) and JetBrains `PasswordSafe` (API key)
 * with environment-variable fallbacks.
 */
internal object AgentSettingsStore {
    const val DEFAULT_BASE_URL = "http://localhost:11434"
    const val DEFAULT_MAX_TURNS = 30

    private const val PREF_AGENT_BASE_URL = "arcdrift.agent.baseUrl"
    private const val PREF_AGENT_MODEL = "arcdrift.agent.model"
    private const val PREF_AGENT_API_KEY = "arcdrift.agent.apiKey"
    private const val PREF_AGENT_MAX_TURNS = "arcdrift.agent.maxTurns"

    private fun apiKeyCredentialAttributes(): CredentialAttributes =
        CredentialAttributes(generateServiceName("ArcDrift", PREF_AGENT_API_KEY))

    fun load(
        getProp: (String) -> String? = defaultPropReader(),
        getIntProp: (String, Int) -> Int = defaultIntPropReader(),
        getSecret: () -> String? = defaultSecretReader(getProp),
        getEnv: (String) -> String? = System::getenv
    ): AgentSettings {
        val baseUrl = getProp(PREF_AGENT_BASE_URL)?.takeIf { it.isNotBlank() }
            ?: getEnv("OPENAI_BASE_URL")?.takeIf { it.isNotBlank() }
            ?: getEnv("OPENAI_API_BASE")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_BASE_URL
        val model = getProp(PREF_AGENT_MODEL)?.takeIf { it.isNotBlank() }
            ?: getEnv("OPENAI_MODEL")?.takeIf { it.isNotBlank() }
            ?: ""
        val savedKey = getSecret()?.takeIf { it.isNotBlank() }
            ?: getProp(PREF_AGENT_API_KEY)?.takeIf { it.isNotBlank() }
        val apiKey = savedKey
            ?: getEnv("OPENAI_API_KEY")
            ?: ""
        val maxTurns = getIntProp(PREF_AGENT_MAX_TURNS, DEFAULT_MAX_TURNS).coerceIn(1, 100)
        return AgentSettings(
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            maxTurns = maxTurns,
            hasSavedApiKey = !savedKey.isNullOrBlank()
        )
    }

    fun save(
        baseUrl: String? = null,
        model: String? = null,
        apiKey: String? = null,
        maxTurns: Int? = null,
        setProp: ((String, String) -> Unit)? = null,
        setIntProp: (String, Int, Int) -> Unit = defaultIntPropWriter(),
        setSecret: ((String) -> Unit)? = null
    ) {
        val propWriter = setProp ?: defaultPropWriter()
        val secretWriter = setSecret ?: if (setProp != null) {
            { secret -> setProp(PREF_AGENT_API_KEY, secret) }
        } else {
            defaultSecretWriter()
        }
        if (!baseUrl.isNullOrBlank()) {
            propWriter(PREF_AGENT_BASE_URL, baseUrl.trim())
        }
        if (model != null) {
            propWriter(PREF_AGENT_MODEL, model.trim())
        }
        if (!apiKey.isNullOrBlank()) {
            secretWriter(apiKey)
        }
        if (maxTurns != null) {
            setIntProp(PREF_AGENT_MAX_TURNS, maxTurns.coerceIn(1, 100), DEFAULT_MAX_TURNS)
        }
    }

    fun resolveForRequest(
        explicitBaseUrl: String?,
        explicitModel: String? = null,
        explicitApiKey: String? = null,
        explicitMaxTurns: Int? = null,
        getProp: (String) -> String? = defaultPropReader(),
        getIntProp: (String, Int) -> Int = defaultIntPropReader(),
        getSecret: () -> String? = defaultSecretReader(getProp),
        getEnv: (String) -> String? = System::getenv
    ): AgentSettings {
        val stored = load(getProp = getProp, getIntProp = getIntProp, getSecret = getSecret, getEnv = getEnv)
        return AgentSettings(
            baseUrl = explicitBaseUrl?.takeIf { it.isNotBlank() }?.trim() ?: stored.baseUrl,
            model = explicitModel?.takeIf { it.isNotBlank() }?.trim() ?: stored.model,
            apiKey = explicitApiKey?.takeIf { it.isNotBlank() } ?: stored.apiKey,
            maxTurns = (explicitMaxTurns ?: stored.maxTurns).coerceIn(1, 100)
        )
    }

    private fun defaultSecretReader(fallbackPropReader: (String) -> String?): () -> String? = {
        runCatching {
            PasswordSafe.instance.getPassword(apiKeyCredentialAttributes())
        }.getOrNull() ?: fallbackPropReader(PREF_AGENT_API_KEY)
    }

    private fun defaultSecretWriter(): (String) -> Unit = { secret ->
        runCatching {
            PasswordSafe.instance.setPassword(apiKeyCredentialAttributes(), secret)
            PropertiesComponent.getInstance().unsetValue(PREF_AGENT_API_KEY)
        }
    }

    private fun defaultPropReader(): (String) -> String? = { key ->
        runCatching { PropertiesComponent.getInstance().getValue(key) }.getOrNull()
    }

    private fun defaultIntPropReader(): (String, Int) -> Int = { key, defaultVal ->
        runCatching { PropertiesComponent.getInstance().getInt(key, defaultVal) }.getOrDefault(defaultVal)
    }

    private fun defaultPropWriter(): (String, String) -> Unit = { key, value ->
        runCatching { PropertiesComponent.getInstance().setValue(key, value) }
    }

    private fun defaultIntPropWriter(): (String, Int, Int) -> Unit = { key, value, defaultVal ->
        runCatching { PropertiesComponent.getInstance().setValue(key, value, defaultVal) }
    }
}
