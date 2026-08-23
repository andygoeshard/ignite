package com.andyl.ignite

import com.andyl.ignite.domain.TrustedDevices
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrustedDevicesTest {

    private fun createStore(): Pair<TrustedDevices, () -> String> {
        var raw: String? = null
        val store = TrustedDevices(
            readRaw = { raw },
            writeRaw = { raw = it },
        )
        return store to { raw.orEmpty() }
    }

    @Test
    fun remember_and_recover_pin() {
        val (store, _) = createStore()
        assertTrue(store.all().isEmpty())
        assertNull(store.pinFor("dev-1"))

        store.remember("dev-1", "Notebook", "192.168.1.5", "123456")
        val entry = store.pinFor("dev-1")
        assertEquals("123456", entry?.pin)
        assertEquals("Notebook", entry?.name)
        assertEquals("192.168.1.5", entry?.host)
        assertTrue(store.isTrusted("dev-1"))
    }

    @Test
    fun remember_updates_existing_pin() {
        val (store, _) = createStore()
        store.remember("dev-1", "Notebook", "192.168.1.5", "111111")
        // El otro regeneró su código y volvimos a emparejar
        store.remember("dev-1", "Notebook", "192.168.1.5", "222222")
        assertEquals("222222", store.pinFor("dev-1")?.pin)
        assertEquals(1, store.all().size)
    }

    @Test
    fun forget_removes_entry() {
        val (store, _) = createStore()
        store.remember("dev-1", "Notebook", "192.168.1.5", "123456")
        store.remember("dev-2", "Celu", "192.168.1.6", "654321")

        assertTrue(store.forget("dev-1"))
        assertFalse(store.isTrusted("dev-1"))
        assertTrue(store.isTrusted("dev-2"))
        // Olvidar dos veces es un no-op honesto
        assertFalse(store.forget("dev-1"))
    }

    @Test
    fun invalid_pin_is_not_saved() {
        val (store, _) = createStore()
        store.remember("dev-1", "Notebook", "192.168.1.5", "12345")   // 5 dígitos
        store.remember("", "Notebook", "192.168.1.5", "123456")       // id vacío
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun corrupted_json_falls_back_to_empty() = runTest {
        var raw: String? = "{not json"
        val store = TrustedDevices(readRaw = { raw }, writeRaw = { raw = it })
        assertTrue(store.all().isEmpty())
        // Y puede recuperarse escribiendo de nuevo
        store.remember("dev-1", "Notebook", "192.168.1.5", "123456")
        assertEquals("123456", store.pinFor("dev-1")?.pin)
    }

    @Test
    fun policy_defaults_to_ask_for_unknown_devices() {
        val (store, _) = createStore()
        assertEquals(com.andyl.ignite.domain.TrustPolicy.ASK, store.policyFor("fantasma"))
    }

    @Test
    fun set_policy_roundtrip_persists() {
        val (store, readRaw) = createStore()
        store.remember("dev-1", "Notebook", "192.168.1.5", "123456")
        assertEquals(com.andyl.ignite.domain.TrustPolicy.ASK, store.policyFor("dev-1"))

        assertEquals(
            com.andyl.ignite.domain.TrustPolicy.AUTO,
            store.setPolicy("dev-1", com.andyl.ignite.domain.TrustPolicy.AUTO)?.policy,
        )
        // Releer desde el "disco" (el mismo raw) confirma persistencia
        assertEquals(com.andyl.ignite.domain.TrustPolicy.AUTO, store.policyFor("dev-1"))
        assertTrue(readRaw().contains("AUTO"))

        store.setPolicy("dev-1", com.andyl.ignite.domain.TrustPolicy.SILENT)
        assertEquals(com.andyl.ignite.domain.TrustPolicy.SILENT, store.policyFor("dev-1"))
        // El PIN sobrevive al cambio de política
        assertEquals("123456", store.pinFor("dev-1")?.pin)
    }

    @Test
    fun set_policy_on_unknown_device_fails_honestly() {
        val (store, _) = createStore()
        assertNull(store.setPolicy("fantasma", com.andyl.ignite.domain.TrustPolicy.AUTO))
    }

    @Test
    fun remember_without_pin_creates_receive_only_trust() {
        val (store, _) = createStore()
        store.remember(
            deviceId = "dev-qr",
            name = "Celu por QR",
            host = "192.168.1.9",
            pin = null,
            policy = com.andyl.ignite.domain.TrustPolicy.AUTO,
        )
        assertTrue(store.isTrusted("dev-qr"))
        assertNull(store.pinFor("dev-qr")?.pin)
        assertEquals(com.andyl.ignite.domain.TrustPolicy.AUTO, store.policyFor("dev-qr"))
    }
}
