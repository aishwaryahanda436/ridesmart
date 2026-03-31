package com.ridesmart.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ridesmart.data.RideEntry
import com.ridesmart.data.RideHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.*

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RideHistoryRepository(application)

    // Expose grouped history to the UI to keep the Main thread smooth
    val groupedHistory: StateFlow<Map<Triple<Int, Int, Int>, List<RideEntry>>> = repository.historyFlow
        .map { history ->
            history.groupBy { entry ->
                val cal = Calendar.getInstance().apply { timeInMillis = entry.timestampMs }
                Triple(
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                )
            }.toSortedMap(compareByDescending<Triple<Int, Int, Int>> { it.first }
                .thenByDescending { it.second }
                .thenByDescending { it.third })
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    val isHistoryEmpty: StateFlow<Boolean> = repository.historyFlow
        .map { it.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearHistory()
        }
    }
}
