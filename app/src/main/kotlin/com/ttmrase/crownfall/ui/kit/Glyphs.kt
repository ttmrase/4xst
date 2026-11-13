package com.ttmrase.crownfall.ui.kit

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R

/**
 * 自作のアイコン一式（tools/artgen/gen_icons.py が res/drawable/ic_*.xml を生成する）。
 * 絵文字は端末ごとに絵が変わり、ゲームの世界観とも合わないので、表示には必ずこれを使う。
 */
enum class Glyph(
    @DrawableRes val res: Int,
) {
    FOOD(R.drawable.ic_res_food),
    WOOD(R.drawable.ic_res_wood),
    STONE(R.drawable.ic_res_stone),
    GOLD(R.drawable.ic_res_gold),
    GEM(R.drawable.ic_res_gem),
    POWER(R.drawable.ic_power),
    BUILD(R.drawable.ic_build),
    RESEARCH(R.drawable.ic_research),
    HEAL(R.drawable.ic_heal),
    INFANTRY(R.drawable.ic_troop_infantry),
    CAVALRY(R.drawable.ic_troop_cavalry),
    ARCHER(R.drawable.ic_troop_archer),
    SIEGE(R.drawable.ic_troop_siege),
    MAIL(R.drawable.ic_mail),
    QUEST(R.drawable.ic_quest),
    BARRACKS(R.drawable.ic_barracks),
    CHAT(R.drawable.ic_chat),
    SETTINGS(R.drawable.ic_settings),
    NAV_CITY(R.drawable.ic_nav_city),
    NAV_MAP(R.drawable.ic_nav_map),
    NAV_ALLIANCE(R.drawable.ic_nav_alliance),
    NAV_HEROES(R.drawable.ic_nav_heroes),
    NAV_SHOP(R.drawable.ic_nav_shop),
    CHEST(R.drawable.ic_chest),
    LOCK(R.drawable.ic_lock),
    CLOCK(R.drawable.ic_clock),
    SPEEDUP(R.drawable.ic_speedup),
    HELP(R.drawable.ic_help),
    GIFT(R.drawable.ic_gift),
    TICKET(R.drawable.ic_ticket),
    SHARD(R.drawable.ic_shard),
    STAR(R.drawable.ic_star),
    VIP(R.drawable.ic_vip),
    CHECK(R.drawable.ic_check),
    PLUS(R.drawable.ic_plus),
    CLOSE(R.drawable.ic_close),
    ARROW_UP(R.drawable.ic_arrow_up),
    INFO(R.drawable.ic_info),
    ALERT(R.drawable.ic_alert),
    TROPHY(R.drawable.ic_trophy),
    MEDAL(R.drawable.ic_medal),
    FLAG(R.drawable.ic_flag),
    SCOUT(R.drawable.ic_scout),
    ATTACK(R.drawable.ic_attack),
    GATHER(R.drawable.ic_gather),
    RALLY(R.drawable.ic_rally),
    WHEEL(R.drawable.ic_wheel),
    PIGGY(R.drawable.ic_piggy),
    CALENDAR(R.drawable.ic_calendar),
    SKIN(R.drawable.ic_skin),
    BOOK(R.drawable.ic_book),
    BAG(R.drawable.ic_bag),
    SHIELD(R.drawable.ic_shield),
    KEY(R.drawable.ic_key),
    BELL(R.drawable.ic_bell),
    MUSIC(R.drawable.ic_music),
    SPARKLE(R.drawable.ic_sparkle),
    TROOPS(R.drawable.ic_troops),
    BACK(R.drawable.ic_back),
    WORLD(R.drawable.ic_world),
    TENT(R.drawable.ic_tent),
    SKULL(R.drawable.ic_skull),
    EXP(R.drawable.ic_exp),
}

private val Grey = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** アイコンを [size] の正方形で描く。[dimmed] は白黒（ロック中・無効）。飾りなので読み上げの対象にしない。 */
@Composable
fun GlyphIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    dimmed: Boolean = false,
    alpha: Float = 1f,
) {
    Image(
        painter = painterResource(glyph.res),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = if (dimmed) Grey else null,
        alpha = alpha,
    )
}
