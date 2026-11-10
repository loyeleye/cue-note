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
 * expects. These check each migration creates every new table exactly as
 * Room's exported schema describes it, so existing lessons survive the update.
 */
class MigrationSqlTest {
    private fun createSql(version: Int, except: Set<String>): List<String> {
        val schema = Json.parseToJsonElement(File("schemas/com.cuenote.data.LessonDatabase/$version.json").readText())
        val entities = schema.jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
        return entities.map { it.jsonObject }
            .filter { it["tableName"]!!.jsonPrimitive.content !in except }
            .map { it["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", it["tableName"]!!.jsonPrimitive.content) }
    }

    @Test fun `migration 1 to 2 matches the exported schema`() {
        assertEquals(createSql(2, setOf("lesson")).sorted(), MIGRATION_1_2_SQL.sorted())   // lesson was there in version 1
    }

    @Test fun `migration 2 to 3 matches the exported schema`() {
        val old = setOf("lesson", "segment", "take", "feedback", "transcript_line", "pipeline_stage")
        assertEquals(createSql(3, old).sorted(), MIGRATION_2_3_SQL.sorted())
        // The tables version 2 already had are unchanged.
        assertEquals(createSql(2, emptySet()).sorted(), createSql(3, setOf("clip", "card")).sorted())
    }

    @Test fun `migration 3 to 4 matches the exported schema`() {
        val three = tables(3)
        val four = tables(4)
        assertEquals(four.filterKeys { it !in three.keys }.values.sorted(), MIGRATION_3_4_SQL.sorted())
        // The tables version 3 already had are unchanged.
        assertEquals(three, four.filterKeys { it in three.keys })
    }

    private fun tables(version: Int): Map<String, String> {
        val schema = Json.parseToJsonElement(File("schemas/com.cuenote.data.LessonDatabase/$version.json").readText())
        return schema.jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }.associate {
            val name = it["tableName"]!!.jsonPrimitive.content
            name to it["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name)
        }
    }
}
