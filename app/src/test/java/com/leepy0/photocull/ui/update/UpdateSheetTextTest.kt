package com.leepy0.photocull.ui.update

import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.R
import org.junit.Test

class UpdateSheetTextTest {
    @Test fun koreanParticleFollowsLastDigit() {
        // 0(영) 3(삼) 6(육) → 으로, 나머지(일·이·사·오·칠·팔·구) → 로
        assertThat(justUpdatedTitleRes("0.2.0")).isEqualTo(R.string.update_just_updated_title_euro)
        assertThat(justUpdatedTitleRes("0.2.3")).isEqualTo(R.string.update_just_updated_title_euro)
        assertThat(justUpdatedTitleRes("0.2.6")).isEqualTo(R.string.update_just_updated_title_euro)
        listOf("0.2.1", "0.2.2", "0.2.4", "0.2.5", "0.2.7", "0.2.8", "0.2.9").forEach {
            assertThat(justUpdatedTitleRes(it)).isEqualTo(R.string.update_just_updated_title_ro)
        }
    }

    @Test fun notesMarkdownIsSimplified() {
        val raw = "\n## 변경 사항\r\n- **흐림** 개선\n  * `연사` 속도\n\n\n\n일반 문장\n\n"
        assertThat(noteLines(raw)).containsExactly(
            NoteLine.Heading("변경 사항"),
            NoteLine.Bullet("흐림 개선"),
            NoteLine.Bullet("연사 속도"),
            NoteLine.Blank,
            NoteLine.Plain("일반 문장"),
        ).inOrder()
        assertThat(noteLines("   \n")).isEmpty()
    }

    @Test fun publishedDateParsing() {
        assertThat(publishedEpoch("2026-10-11T05:03:00Z")).isEqualTo(1_791_694_980_000L)
        assertThat(publishedEpoch("yesterday")).isNull()
        assertThat(publishedEpoch(null)).isNull()
    }
}
