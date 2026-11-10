package com.cuenote.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Room refuses to open a migrated database whose tables differ from what it
 * expects. This checks the 1 → 2 migration creates every new table exactly as
 * Room's exported schema describes it, so existing lessons survive the update.
 */
class MigrationSqlTest {
    @Test fun `migration 1 to 2 matches the exported schema`() {
        val schema = Json.parseToJsonElement(File("schemas/com.cuenote.data.LessonDatabase/2.json").readText())
        val entities = schema.jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
        val expected = entities.map { it.jsonObject }
            .filter { it["tableName"]!!.jsonPrimitive.content != "lesson" }   // already there in version 1
            .map { it["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", it["tableName"]!!.jsonPrimitive.content) }
        assertEquals(expected.sorted(), MIGRATION_1_2_SQL.sorted())
    }

    @Test fun `migration 2 to 3 matches the exported schema`() {
        val two = tables(2).keys
        val expected = tables(3).filterKeys { it !in two }.values
        assertEquals(expected.sorted(), MIGRATION_2_3_SQL.sorted())
    }

    private fun tables(version: Int): Map<String, String> {
        val schema = Json.parseToJsonElement(File("schemas/com.cuenote.data.LessonDatabase/$version.json").readText())
        return schema.jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }.associate {
            val name = it["tableName"]!!.jsonPrimitive.content
            name to it["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name)
        }
    }
}
