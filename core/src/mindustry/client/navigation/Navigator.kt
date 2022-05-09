package mindustry.client.navigation

import arc.*
import arc.math.geom.*
import arc.struct.*
import arc.util.*
import arc.util.pooling.*
import mindustry.Vars.*
import mindustry.client.navigation.waypoints.*
import mindustry.client.utils.*
import mindustry.content.*
import mindustry.game.*
import mindustry.gen.*

/** An abstract class for a navigation algorithm, i.e. A*.  */
abstract class Navigator {
    @JvmField
    val map = HashMap<Int, Vec2>()
    var lastWp = 0L
    private val realObstacles = Seq<Circle>() // Avoids creating new lists every time navigate is called
    companion object {
        var benchmark = false
    }

    init {
        Events.on(EventType.WorldLoadEvent::class.java) {
            map.clear()
        }
    }
    /** Called once upon client loading.  */
    abstract fun init()

    /**
     * Finds a path between the start and end points provided an array of circular obstacles.
     * May return null if no path is found.
     */
    protected abstract fun findPath(
        start: Vec2,
        end: Vec2,
        obstacles: Seq<Circle>,
        width: Float,
        height: Float,
        blocked: Int2P
    ): Array<PositionWaypoint>

    fun navigate(start: Vec2, end: Vec2, obstacles: Iterable<TurretPathfindingEntity>): Array<PositionWaypoint> {
        start.clamp(0f, 0f, world.unitHeight().toFloat(), world.unitWidth().toFloat())
        end.clamp(0f, 0f, world.unitHeight().toFloat(), world.unitWidth().toFloat())
        val additionalRadius =
            if (player.unit().formation == null) player.unit().hitSize / 2
            else player.unit().formation().pattern.radius() + player.unit().formation.pattern.spacing / 2

        if(state.map.name() != "The Maze") {
            synchronized (obstacles) {
                for (turret in obstacles) {
                    if (turret.canHitPlayer && turret.canShoot) {
                        realObstacles.add(
                            Pools.obtain(Circle::class.java) { Circle() }.set(
                                turret.x,
                                turret.y,
                                turret.radius + additionalRadius
                            )
                        )
                    }
                }
            }
        }

        if (state.hasSpawns()) { // FINISHME: These should really be weighed less than turrets...
            for (spawn in spawner.spawns) {
                realObstacles.add(
                    Pools.obtain(Circle::class.java) { Circle() }.set(
                        spawn.worldx(),
                        spawn.worldy(),
                        state.rules.dropZoneRadius + additionalRadius
                    )
                )
            }
        }

        if (Time.timeSinceMillis(lastWp) > 3000) {
            if (map.size > 0) { // CN auto core tp is different as a plugin allows for some magic...
                val closestCore = map.minByOrNull { it.value.dst(end) }!!
                if (player.dst(end) > closestCore.value.dst(end)) {
                    lastWp = Time.millis() // Try again in 3s
                    Call.sendChatMessage("/wp ${closestCore.key}")
                }
            } else if (player.unit().spawnedByCore && !player.unit().isCommanding && player.unit().stack.amount == 0) { // Everything that isn't CN
                val bestCore = player.team().cores().min(Structs.comps(Structs.comparingInt { -it.block.size }, Structs.comparingFloat { it.dst(end) }))
                if (player.dst(end) > bestCore.dst(end) && player.dst(bestCore) > player.unit().speed() * 24) { // don't try to move if we're already close to that core
                    lastWp = Time.millis() // Try again in 3s
                    Call.buildingControlSelect(player, bestCore)
                }
            }
            if (Time.timeSinceMillis(lastWp) > 3000) lastWp = Time.millis() - 2900 // Didn't tp, try again in .1s
        }

        val flood = flood() && player.unit().type != UnitTypes.horizon
        val ret = findPath(
            start, end, realObstacles, world.unitWidth().toFloat(), world.unitHeight().toFloat()
        ) { x, y ->
            flood && world.tiles.getc(x, y).team() == Team.blue || player.unit().type != null && !player.unit().type.canBoost && player.unit().solidity()?.solid(x, y) ?: false
        }
        Pools.freeAll(realObstacles)
        realObstacles.clear()
        return ret
    }

    protected fun interface Int2P {
        operator fun invoke(x: Int, y: Int) : Boolean
    }
}