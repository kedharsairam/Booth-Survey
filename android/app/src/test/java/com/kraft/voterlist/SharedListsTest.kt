package com.kraft.voterlist

import com.kraft.voterlist.data.Booth
import com.kraft.voterlist.data.Lists
import com.kraft.voterlist.data.SubCaste
import com.kraft.voterlist.data.Voter
import com.kraft.voterlist.data.backupJsonOf
import com.kraft.voterlist.data.readBackupOf
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared lists, as the surveyor asked for them.
 *
 * Caste is three groups, not the seven the roll itself is split into. Sub caste belongs to
 * a caste, so choosing OC can only offer OC's sub castes. Party is the four that are worth
 * one tap.
 */
class SharedListsTest {

    // --- what ships -------------------------------------------------------

    @Test
    fun `caste offers the three the ward works in`() {
        assertEquals(listOf("OC", "BC", "SC/ST"), Lists.DEFAULT_CASTES)
    }

    @Test
    fun `the fine-grained roll categories are no longer offered`() {
        // They are on the electoral roll, and they are not questions a surveyor asks at a
        // door. Offering them made him choose between BC-A and BC-B per household.
        for (retired in listOf("BC-A", "BC-B", "BC-C", "BC-D", "SC", "ST")) {
            assertFalse(
                "$retired should not be offered as a caste",
                retired in Lists().castes,
            )
        }
    }

    @Test
    fun `party offers the five`() {
        assertEquals(listOf("TDP", "JSP", "BJP", "YSRCP", "Unknown"), Lists.DEFAULT_PARTIES)
        assertTrue(Lists().parties.contains("Unknown"))
    }

    @Test
    fun `address offers his dad's five areas`() {
        assertEquals(
            listOf(
                "Journalist Colony", "Jetty Tank Road", "Kodugattu", "Others", "Pallapu Street",
            ),
            Lists.DEFAULT_ADDRESSES,
        )
    }

    // --- the sub castes his father gave -----------------------------------

    @Test
    fun `OC offers exactly his father's seven, alphabetically`() {
        assertEquals(
            listOf(
                "Aarya Vaysya", "Brahmin", "Kapu", "Kshatriya", "Muslim", "Others", "Reddy",
            ),
            Lists.DEFAULT_SUBCASTES["OC"],
        )
    }

    @Test
    fun `BC offers exactly his father's eleven, alphabetically`() {
        assertEquals(
            listOf(
                "Devanga", "Kammari", "Karnibathulu", "Naya Bhrammana", "Others",
                "Padmasali", "Pallilu", "Rajika", "Segidi", "Vaddilu", "Visva Bhrammana",
            ),
            Lists.DEFAULT_SUBCASTES["BC"],
        )
    }

    @Test
    fun `SC ST has no sub castes, and none were invented`() {
        // The roll has ST households in it. This ward's survey does not ask, so nothing is
        // offered rather than a plausible-sounding guess that ends up in a spreadsheet.
        assertFalse("SC/ST" in Lists.DEFAULT_SUBCASTES)
        assertEquals(emptyList<String>(), Lists().subCasteGroup("SC/ST"))
    }

    @Test
    fun `a fresh install can work without typing anything in first`() {
        // No stored groups at all, which is every phone that has never had a list edited.
        val fresh = Lists()
        assertEquals(7, fresh.subCasteNamesFor("OC").size)
        assertEquals(11, fresh.subCasteNamesFor("BC").size)
        assertEquals(5, fresh.parties.size)
        assertEquals(5, fresh.addresses.size)
        assertEquals(3, fresh.castes.size)
    }

    @Test
    fun `sub castes come out alphabetically whatever order they were saved in`() {
        // His BC list arrived unsorted. Sorting on the way out rather than on the way in
        // means it is right for a list typed at a door too.
        val jumbled = Lists(subCastes = mapOf("BC" to listOf("Rajika", "Devanga", "Kammari")))
        assertEquals(listOf("Devanga", "Kammari", "Rajika"), jumbled.subCasteGroup("BC"))
    }

    @Test
    fun `a removed sub caste stays removed instead of coming back from the built-in list`() {
        // "Others" is in the built-in OC list. Removing it has to stick, or every reload
        // would put it back and the removal would look like it had failed.
        val after = Lists().withSubCaste("OC", Lists.DEFAULT_SUBCASTES["OC"].orEmpty() - "Others")
        assertFalse("Others" in after.subCasteGroup("OC"))
        assertTrue("Kshatriya" in after.subCasteGroup("OC"))
    }

    @Test
    fun `adding to a caste keeps the built-in list and sorts the new one in`() {
        val after = Lists().withSubCaste(
            "OC", Lists.DEFAULT_SUBCASTES["OC"].orEmpty() + "Vadderla",
        )
        val names = after.subCasteGroup("OC")
        assertEquals(8, names.size)
        assertEquals(names.sortedWith(String.CASE_INSENSITIVE_ORDER), names)
        assertTrue("Vadderla" in names)
    }

    @Test
    fun `a group of his own overrides the built-in one entirely`() {
        val his = Lists(subCastes = mapOf("OC" to listOf("Kapu", "Reddy")))
        assertEquals(listOf("Kapu", "Reddy"), his.subCasteGroup("OC"))
        // The other caste is untouched by that.
        assertEquals(11, his.subCasteGroup("BC").size)
    }

    // --- sub caste follows caste ------------------------------------------

    private val lists = Lists(
        subCastes = mapOf(
            "OC" to listOf("Brahmin", "Kapu"),
            "BC" to listOf("Bedu", "Kurupala"),
            "SC/ST" to listOf("Adi Dravida"),
        ),
    )

    @Test
    fun `choosing a caste offers only that caste's sub castes`() {
        assertEquals(listOf("Brahmin", "Kapu"), lists.subCasteNamesFor("OC"))
        assertEquals(listOf("Bedu", "Kurupala"), lists.subCasteNamesFor("BC"))
        assertEquals(listOf("Adi Dravida"), lists.subCasteNamesFor("SC/ST"))
    }

    @Test
    fun `a BC sub caste is never offered to somebody recorded as OC`() {
        // The whole point. Before this, the dropdown was one flat list, so any sub caste in
        // the ward could be attached to any caste and nothing said so.
        val forOc = lists.subCasteNamesFor("OC")
        assertFalse(forOc.contains("Bedu"))
        assertFalse(forOc.contains("Adi Dravida"))
    }

    @Test
    fun `caste matching ignores case and surrounding space`() {
        // He types it, he does not pick it. "oc" must find the OC group.
        assertEquals(listOf("Brahmin", "Kapu"), lists.subCasteNamesFor(" oc "))
        assertEquals(listOf("Brahmin", "Kapu"), lists.subCasteNamesFor("OC"))
    }

    @Test
    fun `no caste yet offers the unparented list rather than everything`() {
        // Offering the lot is what let the two fields disagree; offering nothing would make
        // a sub caste typed before its caste unreachable. This is the honest middle.
        val withUnparented = lists.withSubCaste(SubCaste.UNPARENTED, listOf("Typed first"))
        assertEquals(listOf("Typed first"), withUnparented.subCasteNamesFor(null))
        assertEquals(listOf("Typed first"), withUnparented.subCasteNamesFor(""))
    }

    @Test
    fun `a caste with a group of its own does not also get the built-in list`() {
        // It used to assert the opposite -- that a caste with no group offers nothing --
        // because at the time a group was only ever something he had typed. Now an absent
        // group means "not customised", which is what makes a fresh install usable.
        val custom = Lists(subCastes = mapOf("OC" to listOf("Brahmin")))
        assertEquals(listOf("Brahmin"), custom.subCasteNamesFor("OC"))
        assertEquals(11, custom.subCasteNamesFor("BC").size)
    }

    @Test
    fun `adding one sub caste leaves the others alone`() {
        val after = lists.withSubCaste("BC", lists.subCastes["BC"].orEmpty() + "Mala")
        assertEquals(listOf("Brahmin", "Kapu"), after.subCasteNamesFor("OC"))
        assertEquals(listOf("Bedu", "Kurupala", "Mala"), after.subCasteNamesFor("BC"))
    }

    @Test
    fun `emptying a caste keeps the group, so it is still somewhere to type`() {
        val after = lists.withSubCaste("OC", emptyList())
        assertEquals(emptyList<String>(), after.subCasteNamesFor("OC"))
        // And it stays empty on reload: a group he emptied is a group he emptied, not one
        // that silently refills itself from the built-in list on the next launch.
        assertEquals(emptyList<String>(), after.subCasteGroup("OC"))
        assertTrue("OC should still be offered as a place to add sub castes", "OC" in after.castesWithSubCastes())
        assertEquals(listOf("Bedu", "Kurupala"), after.subCasteNamesFor("BC"))
    }

    @Test
    fun `every caste gets a group to type into, even one with no sub castes`() {
        assertTrue("BC" in lists.castesWithSubCastes())
        assertTrue("OC" in lists.castesWithSubCastes())
    }

    // --- one panel per caste, never two -----------------------------------

    @Test
    fun `a caste that is both a caste and a sub caste key gets one panel, not two`() {
        // This is what produced two identical "Sub caste · OC" panels in Settings: OC is in
        // the caste list AND, once a sub caste has been typed under it, a key in the sub
        // caste map. The two lists were concatenated with nothing removing the overlap.
        assertEquals(listOf("OC", "BC", "SC/ST"), lists.castesWithSubCastes())
        assertEquals(1, lists.castesWithSubCastes().count { it.equals("OC", true) })
    }

    @Test
    fun `no sub castes typed yet gives one panel per caste`() {
        val empty = Lists()
        assertEquals(Lists.DEFAULT_CASTES, empty.castesWithSubCastes())
    }

    @Test
    fun `a group typed under a different spelling does not become a second panel`() {
        // "oc" typed as a key against a caste list saying "OC": the old concatenation would
        // have added it, drawing a fourth panel for a caste that already had one.
        val odd = Lists(castes = listOf("OC", "BC"), subCastes = mapOf("oc" to listOf("Brahmin")))
        assertEquals(listOf("OC", "BC"), odd.castesWithSubCastes())
    }

    @Test
    fun `a group under a caste that is not on the list still gets a panel`() {
        // His values must not become unreachable because the caste was renamed or retired.
        val odd = Lists(castes = listOf("OC", "BC"), subCastes = mapOf("SC/ST" to listOf("Adi")))
        assertEquals(listOf("OC", "BC", "SC/ST"), odd.castesWithSubCastes())
    }

    @Test
    fun `the unparented bucket is not offered as a caste`() {
        // It is where a sub caste typed before its caste goes. It is not something the voter
        // form can ever offer, so a panel for it would invite filing values under nothing.
        val typed = lists.withSubCaste(SubCaste.UNPARENTED, listOf("Typed first"))
        assertFalse(SubCaste.UNPARENTED in typed.castesWithSubCastes())
    }

    @Test
    fun `setting a group under a different spelling replaces the old one`() {
        // Otherwise "OC" and "oc" become two groups, and the duplicate panel is back --
        // reached by typing this time rather than by loading.
        val after = lists.withSubCaste(" oc ", listOf("Kapu"))
        assertEquals(
            "no group may be added, only replaced",
            lists.subCastes.size, after.subCastes.size,
        )
        assertEquals(
            "exactly one group may match OC in any spelling",
            1, after.subCastes.keys.count { it.equals("OC", ignoreCase = true) },
        )
        assertEquals(listOf("Kapu"), after.subCasteGroup("OC"))
        assertEquals(listOf("Kapu"), after.subCasteGroup("oc"))
        // The other two are untouched.
        assertEquals(listOf("Bedu", "Kurupala"), after.subCasteGroup("BC"))
        assertEquals(listOf("Adi Dravida"), after.subCasteGroup("SC/ST"))
    }

    @Test
    fun `a group is found under any spelling of its caste`() {
        val odd = Lists(subCastes = mapOf("oc" to listOf("Brahmin")))
        assertEquals(listOf("Brahmin"), odd.subCasteGroup("OC"))
        assertEquals(listOf("Brahmin"), odd.subCasteGroup(" OC "))
        assertEquals(listOf("Brahmin"), odd.subCasteNamesFor("OC"))
    }

    @Test
    fun `a caste the app has never heard of falls back to the unparented list`() {
        // The third of the three cases, and the only one with no test on it. He can type a
        // caste that is not one of the three this ward works in. Nothing narrows the sub
        // caste list by it, so the words typed before he got to the caste are what he is
        // offered -- not nothing, which would make them unreachable, and not everything.
        val typed = lists.withSubCaste(SubCaste.UNPARENTED, listOf("Typed first"))
        assertEquals(listOf("Typed first"), typed.subCasteNamesFor("Konda Reddy"))
        // A caste he has already built a group under is not this case: it is known, and
        // offers exactly its own group.
        val withOwn = lists.withSubCaste("Konda Reddy", listOf("Golla"))
        assertEquals(listOf("Golla"), withOwn.subCasteNamesFor("Konda Reddy"))
        assertFalse("the unparented list must not leak into a caste with its own group",
            "Typed first" in withOwn.subCasteNamesFor("Konda Reddy"))
    }

    @Test
    fun `the unparented list is not offered twice`() {
        val typed = lists.withSubCaste(SubCaste.UNPARENTED, listOf("Typed first"))
        assertEquals(listOf("Typed first"), typed.subCasteNamesFor(SubCaste.UNPARENTED))
    }

    // --- what happens on disk ---------------------------------------------

    @Test
    fun `sub caste groups survive a backup round trip`() {
        val booth = Booth(id = "b1", boothNo = "108")
        val back = readBackupOf(backupJsonOf(booth, lists))
        assertNotNull(back)
        assertEquals(listOf("Brahmin", "Kapu"), back!!.lists.subCasteNamesFor("OC"))
        assertEquals(listOf("Adi Dravida"), back.lists.subCasteNamesFor("SC/ST"))
    }

    @Test
    fun `a file written before this change is read, not discarded`() {
        // Every lists.json on every phone written before now has this shape. Its values have
        // no caste attached, so they go to the unparented bucket rather than being lost --
        // somebody typed those at a hundred doorsteps.
        val old = """
            {"kind":"booth-survey-backup","version":3,
             "booth":{"id":"b1","boothNo":"108","voters":[]},
             "lists":{"castes":["OC","BC","ST"],"subCastes":["Brahmin","Bedu"],
                      "addresses":["Main Road"],"parties":["TDP"]}}
        """.trimIndent()
        val b = readBackupOf(old)
        assertNotNull("an old backup must still open", b)
        assertEquals(listOf("Bedu", "Brahmin"), b!!.lists.subCasteNamesFor(null))
        // OC is a caste the app knows, so it offers OC's own list and not everything ever
        // typed. The legacy values stay reachable through the unparented bucket above.
        assertEquals(Lists.DEFAULT_SUBCASTES["OC"], b.lists.subCasteNamesFor("OC"))
        assertEquals(listOf("Main Road"), b.lists.addresses)
    }

    @Test
    fun `reading an old file retires the built-in castes but keeps his typing`() {
        val old = """
            {"kind":"booth-survey-backup","version":3,
             "booth":{"id":"b1","boothNo":"108","voters":[]},
             "lists":{"castes":["BC-A","BC-D","OC","Konda Reddy"],
                      "addresses":[],"parties":[]}}
        """.trimIndent()
        val castes = readBackupOf(old)!!.lists.castes
        assertFalse("built-in BC-A must not come back", "BC-A" in castes)
        assertFalse("built-in BC-D must not come back", "BC-D" in castes)
        assertTrue("a caste he typed is his, and stays", "Konda Reddy" in castes)
        assertTrue("OC stays", "OC" in castes)
    }

    @Test
    fun `an empty saved caste list falls back to the three, not to nothing`() {
        val b = readBackupOf(
            """{"kind":"booth-survey-backup","booth":{"id":"b1","voters":[]},
                "lists":{"castes":[],"subCasteGroups":{}}}"""
        )!!
        assertEquals(Lists.DEFAULT_CASTES, b.lists.castes)
    }

    @Test
    fun `the sub caste group is written as an object keyed by caste`() {
        val booth = Booth(id = "b1", boothNo = "108")
        val text = backupJsonOf(booth, lists)
        val groups = JSONObject(text).getJSONObject("lists").getJSONObject("subCasteGroups")
        assertEquals(3, groups.length())
        assertTrue(groups.getJSONArray("OC").toString().contains("Kapu"))
    }

    @Test
    fun `a voter keeps both fields, and nothing forces one from the other`() {
        // The rule is on the list, not on the record: a person recorded before this change
        // can still hold a sub caste that is not under their caste. We do not rewrite the
        // roll; we stop the dropdown producing new ones.
        val v = Voter(id = "v1", serial = 1, caste = "OC", subCaste = "Bedu")
        assertEquals("OC", v.caste)
        assertEquals("Bedu", v.subCaste)
    }
}