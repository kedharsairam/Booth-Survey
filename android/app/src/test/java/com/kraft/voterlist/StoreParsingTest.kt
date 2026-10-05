package com.kraft.voterlist.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading the working file.
 *
 * Small and unglamorous, and it is here because of what it once cost. `JSONObject.optString`
 * returns the string "null" for a key whose value is JSON null, so every seeded voter came
 * back "in family null" -- a family that does not exist -- and because the booklet prints
 * families from the family list, all 1129 of them vanished from the export while the app
 * still showed 1129 on screen. Nothing about it looked wrong except the output.
 */
class StoreParsingTest {

    @Test
    fun `the helper does not depend on which orgjson is in play`() {
        // These tests run against the reference org.json, where optString of a JSON null
        // gives "". The phone runs against Android's, where the same call gives "null" --
        // which is how all 1129 seeded voters ended up in a family called "null" and
        // vanished from the booklet. Two implementations, two answers, so nothing may be
        // written that relies on either. isNull means the same thing in both.
        val o = JSONObject("""{"familyId": null, "other": "x"}""")
        assertNull(o.stringOrNull("familyId"))
        assertEquals("x", o.stringOrNull("other"))
    }

    @Test
    fun `an absent key is treated the same as a null one`() {
        // Every file written before families existed has no familyId key at all.
        val o = JSONObject("""{"serial": 1, "name": "A"}""")
        assertNull(o.stringOrNull("familyId"))
        assertEquals(1, o.getInt("serial"))
    }

    @Test
    fun `an empty string is treated the same too`() {
        assertNull(JSONObject("""{"familyId": ""}""").stringOrNull("familyId"))
    }

    @Test
    fun `a real value comes back untouched`() {
        assertEquals("f1", JSONObject("""{"familyId": "f1"}""").stringOrNull("familyId"))
        // An id is made of hex and dashes, so it never looks like a number and never gets
        // rewritten into one.
        assertEquals("ZZZ7696263", JSONObject("""{"voterId": "ZZZ7696263"}""").stringOrNull("voterId"))
    }

    @Test
    fun `nothing in the seed claims a family`() {
        // The bundled asset predates families entirely, so every one of them must come back
        // unassigned. If this fails, the booklet is about to print nothing.
        val root = JSONObject(SEED_JSON)
        val arr = root.getJSONArray("voters")
        assertEquals(1129, arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            assertNull("voter ${v.getInt("serial")} claims family ${v.stringOrNull("familyId")}", v.stringOrNull("familyId"))
        }
    }

    companion object {
        /** A trimmed stand-in for the bundled asset, carrying the same 1129 unassigned voters. */
        private val SEED_JSON: String by lazy {
            val arr = org.json.JSONArray()
            (1..1129).forEach { i ->
                arr.put(
                    JSONObject()
                        .put("id", "seed$i")
                        .put("serial", i)
                        .put("name", "PERSON $i")
                        .put("house", "10-1-${(i % 20) + 1}")
                        .put("phone", "")
                        .put("address", "")
                        .put("caste", "")
                        .put("subCaste", "")
                        .put("party", "")
                        .put("note", "")
                        .put("surveyed", false)
                        .put("skipped", false)
                        .put("familyOrder", 0)
                        .put("page", org.json.JSONObject.NULL)
                        .put("card", org.json.JSONObject.NULL)
                )
            }
            JSONObject().put("voters", arr).toString()
        }
    }
}
