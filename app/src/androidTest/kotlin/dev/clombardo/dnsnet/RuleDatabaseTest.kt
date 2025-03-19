/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Contributions shall also be provided under any later versions of the
 * GPL.
 */

package dev.clombardo.dnsnet

import androidx.test.filters.SmallTest
import org.junit.Before
import org.junit.Test
import uniffi.net.NativeHost
import uniffi.net.NativeHostState
import uniffi.net.RuleDatabase
import uniffi.net.RuleDatabaseController

@SmallTest
class RuleDatabaseTest {
    private lateinit var ruleDatabase: RuleDatabase

    @Before
    fun setup() {
        ruleDatabase = RuleDatabase(RuleDatabaseController())
        ruleDatabase.initialize(
            androidFileHelper = FileHelper,
            hostItems = emptyList(),
            hostExceptions = listOf(
                // Single host denied test
                NativeHost(
                    title = "title",
                    data = "singlehostdenied.com",
                    state = NativeHostState.DENY
                ),

                // Single host allowed test
                NativeHost(
                    title = "title",
                    data = "singlehostallowed.com",
                    state = NativeHostState.DENY
                ),
                NativeHost(
                    title = "title",
                    data = "singlehostallowed.com",
                    state = NativeHostState.ALLOW
                ),

                // Single star wildcard denied test
                NativeHost(
                    title = "title",
                    data = "*.starwildcard.denied.com",
                    state = NativeHostState.DENY
                ),

                // Single ABP wildcard denied test
                NativeHost(
                    title = "title",
                    data = "||abpwildcard.denied.com^",
                    state = NativeHostState.DENY
                ),

                // Wildcard exclusion test
                NativeHost(
                    title = "title",
                    data = "*.wildcard.exclusion.com",
                    state = NativeHostState.DENY
                ),
                NativeHost(
                    title = "title",
                    data = "*.spacer.spacer.wildcard.exclusion.com",
                    state = NativeHostState.ALLOW
                ),

                // Wildcard exclusion test reversed
                NativeHost(
                    title = "title",
                    data = "*.wildcardreversed.exclusionreversed.com",
                    state = NativeHostState.ALLOW
                ),
                NativeHost(
                    title = "title",
                    data = "*.block.block.wildcardreversed.exclusionreversed.com",
                    state = NativeHostState.DENY
                ),
            ),
        )
    }

    @Test
    fun singleHostDenied() {
        assert(ruleDatabase.isBlocked("singlehostdenied.com"))
    }

    @Test
    fun singleHostAllowed() {
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
