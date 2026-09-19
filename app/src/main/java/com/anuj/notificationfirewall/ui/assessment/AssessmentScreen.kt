// ui/assessment/AssessmentScreen.kt
package com.anuj.notificationfirewall.ui.assessment

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.data.db.dao.AssessmentDao
import com.anuj.notificationfirewall.data.mapper.AssessmentMappers
import com.anuj.notificationfirewall.domain.assessment.AssessmentAnswers
import com.anuj.notificationfirewall.domain.assessment.AssessmentScorer
import com.anuj.notificationfirewall.domain.assessment.Cost
import com.anuj.notificationfirewall.domain.assessment.Goal
import com.anuj.notificationfirewall.domain.assessment.OpensBucket
import com.anuj.notificationfirewall.domain.assessment.ProblemProfile
import com.anuj.notificationfirewall.domain.assessment.ProtectedApp
import com.anuj.notificationfirewall.domain.assessment.ScrollPattern
import com.anuj.notificationfirewall.domain.assessment.WorstWindow
import com.anuj.notificationfirewall.ui.NfButton
import com.anuj.notificationfirewall.ui.Routes
import com.anuj.notificationfirewall.ui.theme.NfAccent
import com.anuj.notificationfirewall.ui.theme.NfAccentSoft
import com.anuj.notificationfirewall.ui.theme.NfBackground
import com.anuj.notificationfirewall.ui.theme.NfBorder
import com.anuj.notificationfirewall.ui.theme.NfSurface
import com.anuj.notificationfirewall.ui.theme.NfText
import com.anuj.notificationfirewall.ui.theme.NfTextMuted
import com.anuj.notificationfirewall.ui.theme.NfTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

@HiltViewModel
class AssessmentViewModel @Inject constructor(
    private val dao: AssessmentDao,
) : ViewModel() {

    var step by mutableIntStateOf(0)
        private set
    var answers by mutableStateOf(AssessmentAnswers())
        private set

    val totalSteps = QUESTION_COUNT + 1 // questions + diagnosis

    fun pickPattern(v: ScrollPattern) { answers = answers.copy(pattern = v) }
    fun pickWindow(v: WorstWindow) { answers = answers.copy(worstWindow = v) }
    fun pickGoal(v: Goal) { answers = answers.copy(goal = v) }
    fun pickCost(v: Cost) { answers = answers.copy(cost = v) }
    fun pickOpens(v: OpensBucket) { answers = answers.copy(opensPerDayBucket = v) }
    fun pickSleep(startMin: Int?, endMin: Int?) {
        answers = answers.copy(sleepStartMinute = startMin, sleepEndMinute = endMin)
    }
    fun pickReadiness(v: Int) { answers = answers.copy(readiness = v) }

    fun toggleApp(app: ProtectedApp) {
        val current = answers.protectedApps
        answers = if (current.any { it.packageName == app.packageName }) {
            answers.copy(protectedApps = current.filterNot { it.packageName == app.packageName })
        } else {
            answers.copy(protectedApps = current + app)
        }
    }

    fun next() { if (step < totalSteps - 1) step++ }
    fun back() { if (step > 0) step-- }

    fun profile(): ProblemProfile = AssessmentScorer.score(answers)

    fun persistThen(onDone: () -> Unit) {
        val profile = profile()
        viewModelScope.launch {
            dao.insert(AssessmentMappers.toEntity(profile, System.currentTimeMillis()))
            onDone()
        }
    }

    companion object {
        const val QUESTION_COUNT = 8
    }
}

private val trapApps = listOf(
    ProtectedApp("com.instagram.android", "Instagram"),
    ProtectedApp("com.zhiliaoapp.musically", "TikTok"),
    ProtectedApp("com.google.android.youtube", "YouTube"),
    ProtectedApp("com.twitter.android", "X / Twitter"),
    ProtectedApp("com.reddit.frontpage", "Reddit"),
    ProtectedApp("com.facebook.katana", "Facebook"),
    ProtectedApp("com.snapchat.android", "Snapchat"),
    ProtectedApp("com.android.chrome", "Chrome"),
)

@Composable
fun AssessmentScreen(nav: NavHostController, vm: AssessmentViewModel = hiltViewModel()) {
    Column(
        Modifier
            .fillMaxSize()
            .background(NfBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ProgressDots(step = vm.step, total = vm.totalSteps)

        Box(Modifier.weight(1f)) {
            when (vm.step) {
                0 -> SingleChoiceStep(
                    eyebrow = "Question 1 of ${AssessmentViewModel.QUESTION_COUNT}",
                    question = "When you pick up your phone with no reason, where do you end up?",
                    options = ScrollPattern.entries.map { it.name to it.label() },
                    selected = vm.answers.pattern?.name,
                    onPick = { vm.pickPattern(ScrollPattern.valueOf(it)) },
                )
                1 -> SingleChoiceStep(
                    eyebrow = "Question 2 of ${AssessmentViewModel.QUESTION_COUNT}",
                    question = "When does it hurt the most?",
                    options = WorstWindow.entries.map { it.name to it.label() },
                    selected = vm.answers.worstWindow?.name,
                    onPick = { vm.pickWindow(WorstWindow.valueOf(it)) },
                )
                2 -> SingleChoiceStep(
                    eyebrow = "Question 3 of ${AssessmentViewModel.QUESTION_COUNT}",
                    question = "What would a good week give back to you?",
                    options = Goal.entries.map { it.name to it.label() },
                    selected = vm.answers.goal?.name,
                    onPick = { vm.pickGoal(Goal.valueOf(it)) },
                )
                3 -> SingleChoiceStep(
                    eyebrow = "Question 4 of ${AssessmentViewModel.QUESTION_COUNT}",
                    question = "What does it steal from you the most?",
                    options = Cost.entries.map { it.name to it.label() },
                    selected = vm.answers.cost?.name,
                    onPick = { vm.pickCost(Cost.valueOf(it)) },
                )
                4 -> AppMultiSelectStep(vm)
                5 -> SingleChoiceStep(
                    eyebrow = "Question 6 of ${AssessmentViewModel.QUESTION_COUNT}",
                    question = "Roughly how many times a day do you open them?",
                    options = OpensBucket.entries.map { it.name to it.label() },
                    selected = vm.answers.opensPerDayBucket?.name,
                    onPick = { vm.pickOpens(OpensBucket.valueOf(it)) },
                )
                6 -> SleepWindowStep(vm)
                7 -> ReadinessStep(vm)
                else -> DiagnosisStep(vm, nav)
            }
        }

        if (vm.step < vm.totalSteps - 1) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (vm.step > 0) {
                    NfButton("Back", onClick = { vm.back() }, primary = false)
                }
                Box(Modifier.weight(1f)) {
                    NfButton(
                        if (vm.step == 0) "Find out what's pulling at you" else "Continue",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { vm.next() },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ProgressDots(step: Int, total: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(total) { i ->
            Box(
                Modifier
                    .size(width = if (i <= step) 22.dp else 8.dp, height = 8.dp)
                    .clip(CircleShape)
                    .background(if (i <= step) NfAccent else NfBorder),
            )
        }
    }
}

@Composable
private fun StepScaffold(eyebrow: String, question: String, scroll: Boolean = false, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp)
            .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = NfTextMuted)
        Spacer(Modifier.height(8.dp))
        Text(question, style = MaterialTheme.typography.headlineMedium, color = NfTitle)
        Spacer(Modifier.height(20.dp))
        content()
    }
}

@Composable
private fun SingleChoiceStep(
    eyebrow: String,
    question: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onPick: (String) -> Unit,
) {
    StepScaffold(eyebrow, question) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { (id, label) ->
                OptionCard(text = label, selected = selected == id) { onPick(id) }
            }
        }
    }
}

@Composable
private fun AppMultiSelectStep(vm: AssessmentViewModel) {
    StepScaffold(
        "Question 5 of ${AssessmentViewModel.QUESTION_COUNT}",
        "Which apps tend to trap you?",
        scroll = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Pick all that apply — these are the apps Still will protect.",
                style = MaterialTheme.typography.bodyMedium,
                color = NfTextMuted,
            )
            trapApps.forEach { app ->
                val selected = vm.answers.protectedApps.any { it.packageName == app.packageName }
                OptionCard(text = app.label, selected = selected) { vm.toggleApp(app) }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun SleepWindowStep(vm: AssessmentViewModel) {
    val bedtimes = listOf("21:00" to 21 * 60, "22:00" to 22 * 60, "23:00" to 23 * 60, "00:00" to 0, "01:00" to 60)
    val wakes = listOf("05:00" to 5 * 60, "06:00" to 6 * 60, "07:00" to 7 * 60, "08:00" to 8 * 60, "09:00" to 9 * 60)
    StepScaffold(
        "Question 7 of ${AssessmentViewModel.QUESTION_COUNT}",
        "When do you usually sleep?",
        scroll = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Bedtime", style = MaterialTheme.typography.titleSmall, color = NfTextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                bedtimes.forEach { (label, min) ->
                    Chip(
                        label,
                        selected = vm.answers.sleepStartMinute == min,
                        modifier = Modifier.weight(1f),
                    ) { vm.pickSleep(min, vm.answers.sleepEndMinute) }
                }
            }
            Text("Wake-up", style = MaterialTheme.typography.titleSmall, color = NfTextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                wakes.forEach { (label, min) ->
                    Chip(
                        label,
                        selected = vm.answers.sleepEndMinute == min,
                        modifier = Modifier.weight(1f),
                    ) { vm.pickSleep(vm.answers.sleepStartMinute, min) }
                }
            }
        }
    }
}

@Composable
private fun ReadinessStep(vm: AssessmentViewModel) {
    StepScaffold(
        "Question 8 of ${AssessmentViewModel.QUESTION_COUNT}",
        "How ready do you feel to make a change?",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..10).forEach { n ->
                    Chip(
                        n.toString(),
                        selected = vm.answers.readiness == n,
                        modifier = Modifier.weight(1f),
                    ) { vm.pickReadiness(n) }
                }
            }
            Text(
                "There's no wrong answer. Small counts — Still is built for real life.",
                style = MaterialTheme.typography.bodyMedium,
                color = NfTextMuted,
            )
        }
    }
}

@Composable
private fun DiagnosisStep(vm: AssessmentViewModel, nav: NavHostController) {
    val profile = vm.profile()
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Here's what's going on", style = MaterialTheme.typography.labelSmall, color = NfTextMuted)
        Spacer(Modifier.height(10.dp))
        Text(
            AssessmentScorer.diagnosisHeadline(profile),
            style = MaterialTheme.typography.headlineMedium,
            color = NfTitle,
        )
        Spacer(Modifier.height(18.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(NfAccentSoft)
                .border(1.dp, NfAccent.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .padding(16.dp),
        ) {
            Text(
                "Good news: this is one of the most fixable patterns. We've picked a track " +
                    "for it, and set up your phone to help — starting now.",
                style = MaterialTheme.typography.bodyLarge,
                color = NfText,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(AssessmentScorer.diagnosisSupport(profile), style = MaterialTheme.typography.bodyLarge, color = NfTextMuted)
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Your track", style = MaterialTheme.typography.labelSmall, color = NfAccent)
            Text("Tame the Scroll · 6 steps", style = MaterialTheme.typography.titleMedium, color = NfTitle)
        }
        Spacer(Modifier.weight(1f))
        NfButton(
            "Start my track",
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            onClick = {
                vm.persistThen {
                    nav.navigate(Routes.ONBOARDING) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                }
            },
        )
    }
}

@Composable
private fun OptionCard(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) NfAccentSoft else NfSurface)
            .border(1.dp, if (selected) NfAccent else NfBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = if (selected) NfTitle else NfText)
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) NfAccentSoft else NfSurface)
            .border(1.dp, if (selected) NfAccent else NfBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) NfTitle else NfTextMuted)
    }
}

private fun ScrollPattern.label() = when (this) {
    ScrollPattern.REELS -> "Instagram / TikTok reels"
    ScrollPattern.NEWS -> "News / doomscrolling"
    ScrollPattern.YOUTUBE -> "YouTube for hours"
    ScrollPattern.CHECKING -> "Just checking, over and over"
}

private fun WorstWindow.label() = when (this) {
    WorstWindow.MORNING -> "First thing in the morning"
    WorstWindow.NIGHT -> "Late at night in bed"
    WorstWindow.WORK -> "When I should be working"
    WorstWindow.ALL_DAY -> "All day, in bursts"
}

private fun Goal.label() = when (this) {
    Goal.CALM_MORNINGS -> "Calmer mornings"
    Goal.REAL_SLEEP -> "Real sleep"
    Goal.FOCUSED_WORK -> "Focused work"
    Goal.LESS_NOISE -> "Just… less noise"
}

private fun Cost.label() = when (this) {
    Cost.FOCUS -> "Deep focus"
    Cost.SLEEP -> "My sleep"
    Cost.MOOD -> "My mood"
    Cost.PEOPLE -> "Time with people"
}

private fun OpensBucket.label() = when (this) {
    OpensBucket.FEW -> "Under 10"
    OpensBucket.SOME -> "10–30"
    OpensBucket.MANY -> "30–80"
    OpensBucket.CONSTANT -> "80+ — constant"
}
