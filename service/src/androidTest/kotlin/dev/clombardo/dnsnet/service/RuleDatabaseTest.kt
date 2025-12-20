/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Contributions shall also be provided under any later versions of the
 * GPL.
 */

package dev.clombardo.dnsnet.service

import androidx.test.filters.SmallTest
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import uniffi.netAndroid.NativeFilter
import uniffi.netAndroid.NativeFilterState
import uniffi.netAndroid.RuleDatabase
import uniffi.netAndroid.RuleDatabaseController

@SmallTest
class RuleDatabaseTest {
    private lateinit var ruleDatabase: RuleDatabase

    @Before
    fun setup() {
        ruleDatabase = RuleDatabase(RuleDatabaseController())
        ruleDatabase.initialize(
            androidFileHelper = NativeFileHelperWrapper(ApplicationProvider.getApplicationContext()),
            filterFiles = emptyList(),
            singleFilters = listOf(
                // Single host denied test
                NativeFilter(
                    title = "title",
                    data = "singlehostdenied.com",
                    state = NativeFilterState.DENY
                ),

                // Single host allowed test
                NativeFilter(
                    title = "title",
                    data = "singlehostallowed.com",
                    state = NativeFilterState.DENY
                ),
                NativeFilter(
                    title = "title",
                    data = "singlehostallowed.com",
                    state = NativeFilterState.ALLOW
                ),

                // Single star wildcard denied test
                NativeFilter(
                    title = "title",
                    data = "*.starwildcard.denied.com",
                    state = NativeFilterState.DENY
                ),

                // Single ABP wildcard denied test
                NativeFilter(
                    title = "title",
                    data = "||abpwildcard.denied.com^",
                    state = NativeFilterState.DENY
                ),

                // Wildcard exclusion test
                NativeFilter(
                    title = "title",
                    data = "*.wildcard.exclusion.com",
                    state = NativeFilterState.DENY
                ),
                NativeFilter(
                    title = "title",
                    data = "*.spacer.spacer.wildcard.exclusion.com",
                    state = NativeFilterState.ALLOW
                ),

                // Wildcard exclusion test reversed
                NativeFilter(
                    title = "title",
                    data = "*.wildcardreversed.exclusionreversed.com",
                    state = NativeFilterState.ALLOW
                ),
                NativeFilter(
                    title = "title",
                    data = "*.block.block.wildcardreversed.exclusionreversed.com",
                    state = NativeFilterState.DENY
                ),
            ),
        )
    }

    @Test
    fun singleHostNameDenied() {
        assert(ruleDatabase.isBlocked("singlehostdenied.com"))
    }

    @Test
    fun singleHostNameAllowed() {
        assert(!ruleDatabase.isBlocked("singlehostallowed.com"))
    }

    @Test
    fun singleStarWildcardAllowed() {
        assert(ruleDatabase.isBlocked("starwildcard.denied.com"))
        assert(ruleDatabase.isBlocked("one.starwildcard.denied.com"))
        assert(ruleDatabase.isBlocked("one.two.starwildcard.denied.com"))
        assert(!ruleDatabase.isBlocked("denied.com"))
    }

    @Test
    fun singleABPWildcardAllowed() {
        assert(ruleDatabase.isBlocked("abpwildcard.denied.com"))
        assert(ruleDatabase.isBlocked("one.abpwildcard.denied.com"))
        assert(ruleDatabase.isBlocked("one.two.abpwildcard.denied.com"))
        assert(!ruleDatabase.isBlocked("denied.com"))
    }

    @Test
    fun wildcardExclusionAllowed() {
        assert(ruleDatabase.isBlocked("wildcard.exclusion.com"))
        assert(!ruleDatabase.isBlocked("spacer.spacer.wildcard.exclusion.com"))
        assert(!ruleDatabase.isBlocked("spacer.spacer.spacer.wildcard.exclusion.com"))
        assert(ruleDatabase.isBlocked("spacer.wildcard.exclusion.com"))
    }

    @Test
    fun wildcardExclusionReversedAllowed() {
        assert(!ruleDatabase.isBlocked("wildcardreversed.exclusionreversed.com"))
        assert(ruleDatabase.isBlocked("block.block.wildcardreversed.exclusionreversed.com"))
        assert(ruleDatabase.isBlocked("block.block.block.wildcardreversed.exclusionreversed.com"))
        assert(!ruleDatabase.isBlocked("block.wildcardreversed.exclusionreversed.com"))
    }
}
