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

    @Test fun `migration 4 to 5 matches the exported schema`() {
        val four = tables(4)
        val five = tables(5)
        assertEquals(five.filterKeys { it !in four.keys }.values.sorted(), MIGRATION_4_5_SQL.sorted())
        assertEquals(four, five.filterKeys { it in four.keys })
    }

    @Test fun `migration 5 to 6 matches the exported schema`() {
        val five = tables(5)
        val six = tables(6)
        val created = MIGRATION_5_6_SQL.filter { it.startsWith("CREATE") }
        assertEquals(six.filterKeys { it !in five.keys }.values.sorted(), created.sorted())
        // Old tables are unchanged, except for the columns the migration adds at their end.
        five.forEach { (name, sql) ->
            val added = MIGRATION_5_6_ADDED_COLUMNS[name].orEmpty()
            val expected = if (added.isEmpty()) sql
            else sql.replace(", PRIMARY KEY(", ", " + added.joinToString(", ") + ", PRIMARY KEY(")
            assertEquals(name, expected, six[name])
        }
    }

    @Test fun `migration 6 to 7 matches the exported schema`() {
        val six = tables(6)
        val seven = tables(7)
        val created = MIGRATION_6_7_SQL.filter { it.startsWith("CREATE") }
        assertEquals(seven.filterKeys { it !in six.keys }.values.sorted(), created.sorted())
        six.forEach { (name, sql) ->
            val added = MIGRATION_6_7_ADDED_COLUMNS[name].orEmpty()
            val expected = if (added.isEmpty()) sql
            else sql.replace(", PRIMARY KEY(", ", " + added.joinToString(", ") + ", PRIMARY KEY(")
            assertEquals(name, expected, seven[name])
        }
    }

    @Test fun `migration 7 to 8 matches the exported schema`() {
        val seven = tables(7)
        val eight = tables(8)
        assertEquals(seven.keys, eight.keys)
        seven.forEach { (name, sql) ->
            val added = MIGRATION_7_8_ADDED_COLUMNS[name].orEmpty()
            val expected = if (added.isEmpty()) sql
            else sql.replace(", PRIMARY KEY(", ", " + added.joinToString(", ") + ", PRIMARY KEY(")
            assertEquals(name, expected, eight[name])
        }
    }

    @Test fun `migration 8 to 9 matches the exported schema`() {
        val eight = tables(8)
        val nine = tables(9)
        assertEquals(eight.keys, nine.keys)
        eight.forEach { (name, sql) ->
            val added = MIGRATION_8_9_ADDED_COLUMNS[name].orEmpty()
            val expected = if (added.isEmpty()) sql
            else sql.replace(", PRIMARY KEY(", ", " + added.joinToString(", ") + ", PRIMARY KEY(")
            assertEquals(name, expected, nine[name])
        }
    }

    @Test fun `migration 9 to 10 matches the exported schema`() {
        val nine = tables(9)
        val ten = tables(10)
        assertEquals(nine.keys, ten.keys)
        nine.forEach { (name, sql) ->
            val added = MIGRATION_9_10_ADDED_COLUMNS[name].orEmpty()
            val expected = if (added.isEmpty()) sql
            else sql.replace(", PRIMARY KEY(", ", " + added.joinToString(", ") + ", PRIMARY KEY(")
            assertEquals(name, expected, ten[name])
        }
    }

    @Test fun `migration 10 to 11 matches the exported schema`() {
        val ten = tables(10)
        val eleven = tables(11)
        assertEquals(ten.keys, eleven.keys)
        ten.forEach { (name, sql) ->
            val added = MIGRATION_10_11_ADDED_COLUMNS[name].orEmpty()
            val expected = if (added.isEmpty()) sql
            else sql.replace(", PRIMARY KEY(", ", " + added.joinToString(", ") + ", PRIMARY KEY(")
            assertEquals(name, expected, eleven[name])
        }
    }

    @Test fun `migration 11 to 12 matches the exported schema`() {
        val eleven = tables(11)
        val twelve = tables(12)
        assertEquals(eleven.keys, twelve.keys)
        eleven.forEach { (name, sql) ->
            val added = MIGRATION_11_12_ADDED_COLUMNS[name].orEmpty()
            val expected = if (added.isEmpty()) sql
            else sql.replace(", PRIMARY KEY(", ", " + added.joinToString(", ") + ", PRIMARY KEY(")
            assertEquals(name, expected, twelve[name])
        }
    }

    @Test fun `migration 12 to 13 matches the exported schema`() {
        val twelve = tables(12)
        val thirteen = tables(13)
        assertEquals(thirteen.filterKeys { it !in twelve.keys }.values.sorted(), MIGRATION_12_13_SQL.sorted())
        assertEquals(twelve, thirteen.filterKeys { it in twelve.keys })
    }

    @Test fun `migration 13 to 14 matches the exported schema`() {
        val thirteen = tables(13)
        val fourteen = tables(14)
        assertEquals(thirteen.keys, fourteen.keys)
        thirteen.forEach { (name, sql) ->
            val added = MIGRATION_13_14_ADDED_COLUMNS[name].orEmpty()
            val expected = if (added.isEmpty()) sql
            else sql.replace(", PRIMARY KEY(", ", " + added.joinToString(", ") + ", PRIMARY KEY(")
            assertEquals(name, expected, fourteen[name])
        }
    }

    @Test fun `migration 14 to 15 matches the exported schema`() {
        val fourteen = tables(14)
        val fifteen = tables(15)
        assertEquals(fourteen.keys, fifteen.keys)
        fourteen.forEach { (name, sql) ->
            val added = MIGRATION_14_15_ADDED_COLUMNS[name].orEmpty()
            val expected = if (added.isEmpty()) sql
            else sql.replace(", PRIMARY KEY(", ", " + added.joinToString(", ") + ", PRIMARY KEY(")
            assertEquals(name, expected, fifteen[name])
        }
    }

    private fun tables(version: Int): Map<String, String> {
        val schema = Json.parseToJsonElement(File("schemas/com.cuenote.data.LessonDatabase/$version.json").readText())
        return schema.jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }.associate {
            val name = it["tableName"]!!.jsonPrimitive.content
            name to it["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name)
        }
    }
}
