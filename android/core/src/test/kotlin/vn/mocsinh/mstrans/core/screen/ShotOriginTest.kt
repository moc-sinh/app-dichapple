package vn.mocsinh.mstrans.core.screen

import kotlin.test.Test
import kotlin.test.assertEquals

class ShotOriginTest {
    private val screen = Box(0, 0, 1080, 2400)

    @Test
    fun aWindowShotTheSizeOfTheWindowStartsAtItsCorner() {
        assertEquals(0 to 0, ShotOrigin.of(1080, 2400, screen, screen))
        assertEquals(0 to 330, ShotOrigin.of(1080, 2064, Box(0, 330, 1080, 2394), screen))
    }

    @Test
    fun aWindowWithAShadowIsShotWithRoomAroundIt() {
        // A dialog 1080 × 2064 at y 330 with a shadow of 88 px each side (Taobao's sheet): the
        // picture is 1256 × 2240 and its corner is 88 px above and left of the window's.
        assertEquals(-88 to 242, ShotOrigin.of(1256, 2240, Box(0, 330, 1080, 2394), screen))
    }

    @Test
    fun aShotOfTheWholeScreenStartsAtTheScreensCorner() {
        assertEquals(0 to 0, ShotOrigin.of(1080, 2400, Box(0, 330, 1080, 2394), screen))
    }

    @Test
    fun aSmallerPictureIsTakenAtTheWindowsCorner() {
        assertEquals(0 to 330, ShotOrigin.of(1000, 2000, Box(0, 330, 1080, 2394), screen))
    }
}
