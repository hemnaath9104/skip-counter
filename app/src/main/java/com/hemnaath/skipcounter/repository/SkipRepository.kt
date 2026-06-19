package com.hemnaath.skipcounter.repository

import android.app.Application
import com.hemnaath.skipcounter.viewmodel.CounterViewModel

/**
 * SkipRepository: Data abstraction layer.
 * Currently a stub — will add Room DB persistence in Phase 2.
 */
class SkipRepository(application: Application) {

    /**
     * Save a completed session to the database.
     * Currently does nothing (stub).
     * Phase 2: Will insert into Room DB.
     */
    suspend fun saveSession(result: CounterViewModel.SessionResult) {
        // TODO: Insert into Room database
    }
}