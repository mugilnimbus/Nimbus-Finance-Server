package com.nimbus.finance.server

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    @Test
    fun syncOperationRoundTripKeepsMinorUnitPayload() {
        val payload = buildJsonObject { put("amountMinor", 12_345L) }
        val operation = SyncOperation(
            operationId = "00000000-0000-0000-0000-000000000001",
            entityType = "TRANSACTION",
            entityId = "00000000-0000-0000-0000-000000000002",
            action = "UPSERT",
            scopeType = "PERSONAL",
            scopeId = "SELF",
            payload = payload
        )
        assertEquals(12_345L, operation.payload!!.jsonObject["amountMinor"]!!.toString().toLong())
    }

    @Test
    fun syncBatchOrdersReferenceParentsBeforeChildrenAndReversesDeletes() {
        val transaction = operation("TRANSACTION", "UPSERT", 1)
        val schedule = operation("SCHEDULED_PAYMENT", "UPSERT", 2)
        val account = operation("FINANCIAL_ACCOUNT", "UPSERT", 3)
        val deleteAccount = operation("FINANCIAL_ACCOUNT", "DELETE", 4)
        val deleteTransaction = operation("TRANSACTION", "DELETE", 5)

        assertEquals(
            listOf(account, schedule, transaction, deleteTransaction, deleteAccount),
            orderedSyncOperations(listOf(transaction, deleteAccount, schedule, deleteTransaction, account))
        )
    }

    @Test
    fun enrollmentDeepLinkContainsNoPasswordOrSessionToken() {
        val link = enrollmentDeepLink("https://nimbus-finance.example.ts.net", "ABCDEF234567")
        assertEquals(
            "nimbus://connect?server=https%3A%2F%2Fnimbus-finance.example.ts.net&invite=ABCDEF234567",
            link
        )
        assertTrue("password" !in link && "token" !in link)
    }

    @Test
    fun qrSvgIsSelfContainedAndPurple() {
        val svg = qrSvg("nimbus://connect?server=https%3A%2F%2Ffinance.example.ts.net")
        assertTrue(svg.startsWith("<svg"))
        assertTrue("#2e1065" in svg)
        assertTrue("<path" in svg)
    }

    @Test
    fun ownerLaunchTokenIsSingleUseAndCreatesShortSession() {
        val access = DashboardAccess("owner-secret")
        assertNull(access.createLaunchToken("wrong"))
        val launch = assertNotNull(access.createLaunchToken("owner-secret"))
        val session = assertNotNull(access.redeemLaunchToken(launch))
        assertNull(access.redeemLaunchToken(launch))
        assertTrue(access.isAuthenticated(session))
        access.logout(session)
        assertTrue(!access.isAuthenticated(session))
    }

    @Test
    fun dashboardResourcesDoNotDependOnExternalScripts() {
        val html = assertNotNull(javaClass.classLoader.getResourceAsStream("dashboard/index.html")).bufferedReader().use { it.readText() }
        val icon = assertNotNull(javaClass.classLoader.getResourceAsStream("dashboard/icon.svg")).bufferedReader().use { it.readText() }
        assertTrue("/dashboard/app.js" in html)
        assertTrue("src=\"/dashboard/icon.svg\"" in html)
        assertTrue("rel=\"icon\"" in html)
        assertTrue("#5B2F8F" in icon && "#7543BF" in icon && "#A78BFA" in icon && "#7C3AED" in icon)
        assertTrue("M34 58h9v11h-9zM50 44h9v25h-9zM66 33h9v36h-9z" in icon)
        assertTrue("src=\"https://" !in html && "href=\"https://" !in html)
    }

    @Test
    fun dashboardAsyncFormsRetainTheirFormBeforeAwaiting() {
        val script = assertNotNull(javaClass.classLoader.getResourceAsStream("dashboard/app.js")).bufferedReader().use { it.readText() }
        assertTrue("const formElement = event.currentTarget" in script)
        assertTrue("event.currentTarget.reset()" !in script)
    }

    @Test
    fun dashboardCookieIsLocalhostCompatibleAndSecureOnPrivateHttps() {
        val publicUrl = "https://nimbus-finance.example.ts.net"
        assertTrue(!adminCookieRequiresSecure("127.0.0.1", publicUrl))
        assertTrue(!adminCookieRequiresSecure("localhost", publicUrl))
        assertTrue(adminCookieRequiresSecure("nimbus-finance.example.ts.net", publicUrl))
    }

    @Test
    fun trashMigrationAddsRetentionColumnsAndHistoricalSetNullReferences() {
        val migration = assertNotNull(javaClass.classLoader.getResourceAsStream("db/migration/002_account_trash_and_deletion.sql"))
            .bufferedReader().use { it.readText() }
        assertTrue("purge_after TIMESTAMPTZ" in migration)
        assertTrue("'TRASHED'" in migration)
        assertTrue("ON DELETE SET NULL" in migration)
    }

    private fun operation(entityType: String, action: String, suffix: Int) = SyncOperation(
        operationId = "00000000-0000-0000-0000-${suffix.toString().padStart(12, '0')}",
        entityType = entityType,
        entityId = "10000000-0000-0000-0000-${suffix.toString().padStart(12, '0')}",
        action = action,
        scopeType = "PERSONAL",
        scopeId = "SELF",
        payload = if (action == "UPSERT") buildJsonObject { put("test", true) } else null
    )
}
