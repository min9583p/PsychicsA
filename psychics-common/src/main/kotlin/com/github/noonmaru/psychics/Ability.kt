/*
 * Copyright (c) 2020 Noonmaru
 *
 *  Licensed under the General Public License, Version 3.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/gpl-3.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.github.noonmaru.psychics

import com.github.noonmaru.psychics.damage.Damage
import com.github.noonmaru.psychics.damage.psychicDamage
import com.github.noonmaru.psychics.util.TargetFilter
import com.github.noonmaru.psychics.util.Tick
import com.github.noonmaru.tap.ref.UpstreamReference
import net.md_5.bungee.api.ChatColor
import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.event.player.PlayerEvent
import kotlin.math.max

abstract class Ability<T : AbilityConcept> {

    lateinit var concept: T
        private set

    var cooldownTicks: Long = 0L
        get() {
            return max(0L, field - Tick.currentTicks)
        }
        set(value) {
            checkState()

            val ticks = max(0L, value)
            field = Tick.currentTicks + ticks

            val wand = concept.internalWand

            if (wand != null) {
                esper.player.setCooldown(wand.type, ticks.toInt())
            }
        }

    private lateinit var psychicRef: UpstreamReference<Psychic>

    val psychic: Psychic
        get() = psychicRef.get()

    val esper
        get() = psychic.esper

    @Suppress("UNCHECKED_CAST")
    internal fun initConcept(concept: AbilityConcept) {
        this.concept = concept as T
    }

    internal fun initPsychic(psychic: Psychic) {
        this.psychicRef = UpstreamReference(psychic)
    }

    open fun test(): TestResult {
        val psychic = psychic

        if (!psychic.isEnabled) return TestResult.FAILED_DISABLED
        if (esper.player.level < concept.levelRequirement) return TestResult.FAILED_LEVEL
        if (cooldownTicks > 0L) return TestResult.FAILED_COOLDOWN
        if (psychic.mana < concept.cost) return TestResult.FAILED_COST

        return TestResult.SUCCESS
    }

    internal fun save(config: ConfigurationSection) {
        config[COOLDOWN_TICKS] = cooldownTicks

        runCatching {
            onSave(config)
        }
    }

    internal fun load(config: ConfigurationSection) {
        cooldownTicks = max(0L, config.getLong(COOLDOWN_TICKS))

        runCatching {
            onLoad(config)
        }
    }

    companion object {
        private const val COOLDOWN_TICKS = "cooldown-ticks"
    }

    /**
     * ????????? ??? ???????????????.
     */
    open fun onInitialize() {}

    /**
     * ?????????????????? ?????? ??? ???????????????.
     */
    open fun onAttach() {}

    /**
     * ????????????????????? ?????? ??? ???????????????.
     */
    open fun onDetach() {}

    /**
     * ????????? ???????????? ?????? ??? ??? ???????????????.
     */
    open fun onSave(config: ConfigurationSection) {}

    /**
     * ????????? ?????????????????? ?????? ??? ??? ???????????????.
     */
    open fun onLoad(config: ConfigurationSection) {}

    /**
     * ????????? ????????? ??? ??? ???????????????.
     */
    open fun onEnable() {}

    /**
     * ????????? ???????????? ??? ??? ???????????????.
     */
    open fun onDisable() {}

    fun checkState() {
        psychic.checkState()
    }

    fun checkEnabled() {
        psychic.checkEnabled()
    }

    fun LivingEntity.psychicDamage(
        damage: Damage,
        knockbackLocation: Location? = esper.player.location,
        knockback: Double = 0.0
    ) {
        val type = damage.type
        val amount = esper.getStatistic(damage.stats)

        psychicDamage(this@Ability, type, amount, esper.player, knockbackLocation, knockback)
    }
}

abstract class ActiveAbility<T : AbilityConcept> : Ability<T>() {
    var targeter: (() -> Any?)? = null

    override fun test(): TestResult {
        if (psychic.channeling != null) return TestResult.FAILED_CHANNEL

        return super.test()
    }

    open fun tryCast(
        event: PlayerEvent,
        action: WandAction,
        castingTicks: Long = concept.castingTicks,
        cost: Double = concept.cost,
        targeter: (() -> Any?)? = this.targeter
    ): TestResult {
        val result = test()

        if (result === TestResult.SUCCESS) {
            var target: Any? = null

            if (targeter != null) {
                target = targeter.invoke() ?: return TestResult.FAILED_TARGET
            }

            return if (psychic.mana >= concept.cost) {
                cast(event, action, castingTicks, target)
                TestResult.SUCCESS
            } else {
                TestResult.FAILED_COST
            }
        }

        return result
    }

    protected fun cast(
        event: PlayerEvent,
        action: WandAction,
        castingTicks: Long,
        target: Any? = null
    ) {
        checkState()

        if (castingTicks > 0) {
            psychic.startChannel(this, event, action, castingTicks, target)
        } else {
            onCast(event, action, target)
        }
    }

    abstract fun onCast(event: PlayerEvent, action: WandAction, target: Any?)

    open fun onChannel(channel: Channel) {}

    open fun onInterrupt(channel: Channel) {}

    enum class WandAction {
        LEFT_CLICK,
        RIGHT_CLICK
    }
}

fun Ability<*>.targetFilter(): TargetFilter {
    return TargetFilter(esper.player)
}

class TestResult private constructor(
    val message: String,
    val messageFormatter: ((message: String, ability: Ability<*>) -> String)?
) {
    companion object {
        val SUCCESS = create("??????")
        val FAILED_LEVEL = create("${ChatColor.BOLD}????????? ???????????????") { message, ability ->
            "$message ${ChatColor.RESET}(${ability.concept.levelRequirement}${ChatColor.BOLD}??????)"
        }
        val FAILED_DISABLED = create("${ChatColor.BOLD}????????? ?????? ??? ??? ????????????.")
        val FAILED_COOLDOWN = create("${ChatColor.BOLD}?????? ???????????? ???????????????.") { message, ability ->
            "$message ${ChatColor.RESET}(${(ability.cooldownTicks + 19) / 20}${ChatColor.BOLD}???)"
        }
        val FAILED_COST = create("${ChatColor.BOLD}????????? ???????????????.") { message, ability ->
            "$message ${ChatColor.RESET}(${ability.concept.cost.toInt()})"
        }
        val FAILED_TARGET = create("${ChatColor.BOLD}?????? ?????? ????????? ???????????? ???????????????.")
        val FAILED_CHANNEL = create("${ChatColor.BOLD}???????????? ????????? ????????????.")

        fun create(message: String, formatter: ((message: String, ability: Ability<*>) -> String)? = null): TestResult {
            return TestResult(message, formatter)
        }
    }

    fun getMessage(ability: Ability<*>): String {
        val formatter = messageFormatter ?: return message

        return formatter.invoke(message, ability)
    }
}