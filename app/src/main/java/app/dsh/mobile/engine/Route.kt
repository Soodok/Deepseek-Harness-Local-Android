package app.dsh.mobile.engine

import android.content.Context

/** A single HTTP bridge route. The bridge owns dispatch; capabilities own behavior. */
data class Route(
    val method: String,
    val handle: (Context, String, String) -> Pair<Int, String>,
)
