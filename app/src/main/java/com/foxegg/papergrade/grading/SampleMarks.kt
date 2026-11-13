package com.foxegg.papergrade.grading

import com.foxegg.papergrade.model.Mark

object SampleMarks {
    const val SPEC_JSON =
        """{"marks":[{"type":"check","x":0.72,"y":0.18},{"type":"cross","x":0.72,"y":0.31},{"type":"text","x":0.80,"y":0.45,"text":"要点不完整"}]}"""

    fun load(): List<Mark> = parseMarks(SPEC_JSON)
}
