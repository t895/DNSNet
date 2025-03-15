/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui

import android.annotation.SuppressLint
import androidx.navigation.NavController
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute

/**
 * Wrapper around [NavController.popBackStack] that prevents you from popping every item in the backstack.
 *
 * @param id ID from [NavBackStackEntry.id]
 */
@SuppressLint("RestrictedApi")
fun NavController.tryPopBackstack(id: String): Boolean {
    if (currentBackStack.value.size > 2) {
        if (currentBackStack.value.any { it.id == id }) {
            return popBackStack()
        }
    }
    return false
}

/**
 * Wrapper around [NavController.navigate] that navigates to a destination and removes all previous
 * backstack entries while saving state.
 *
 * @param route Typed route to navigate to
 */
fun <T : Any> NavController.popNavigate(route: T) {
    navigate(route) {
        popUpTo(0) {
            saveState = true
            inclusive = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Checks if the current backstack contains a typed route.
 *
 * @param T Type of route to search for
 */
@SuppressLint("RestrictedApi")
inline fun <reified T : Any> NavController.containsRoute(): Boolean =
    currentBackStack.value.any { it.destination.hasRoute<T>() }
