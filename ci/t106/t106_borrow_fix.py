#!/usr/bin/env python3
"""T106: B233 resource borrow without borrowID, based on real E5 wire trace + CatPlay.

Strict additive transform applied after verified T104-v6 and T105 patches.
No signing, publication, network or other app changes.
"""
import argparse
from pathlib import Path

STATE = 'app/src/main/java/io/github/shaonialife/cardeck/airplay/B233ResourceState.kt'
TEST = 'app/src/test/java/io/github/shaonialife/cardeck/airplay/B233ResourceStateTest.kt'


def replace_once(s, old, new, label):
    n = s.count(old)
    if n != 1:
        raise ValueError(f'STOP_T106_{label}_matches={n}')
    return s.replace(old, new, 1)


def patch_state(s):
    s = replace_once(s, '''        val borrowIds: MutableSet<String> = linkedSetOf(),
        var borrowOverflow: Boolean = false,''', '''        val borrowIds: MutableSet<String> = linkedSetOf(),
        // E5 wire trace sends transferType=3/4 with no borrowID.
        // CatPlay's resource manager counts such borrows; treating them as
        // unrecoverable made a matched UNBORROW unable to restore the display.
        var anonymousBorrowCount: Int = 0,
        var borrowOverflow: Boolean = false,''', 'fields')
    s = replace_once(s, '''            get() = if (borrowOverflow || borrowIds.isNotEmpty()) CAR else permanentEntity''', '''            get() = if (borrowOverflow || anonymousBorrowCount > 0 || borrowIds.isNotEmpty()) CAR else permanentEntity''', 'effective_owner')
    s = replace_once(s, '''            it.borrowIds.clear()
            it.borrowOverflow = false''', '''            it.borrowIds.clear()
            it.anonymousBorrowCount = 0
            it.borrowOverflow = false''', 'reset')
    s = replace_once(s, '''                3L -> {
                    val borrowId = boundedBorrowId(item["borrowID"])
                    if (borrowId == null) {
                        // Unknown borrow cannot be safely matched by a future UNBORROW.
                        resource.borrowOverflow = true
                        return@forEach
                    }
                    if (borrowId !in resource.borrowIds) {
                        if (resource.borrowIds.size < MAX_ACTIVE_BORROWS) {
                            resource.borrowIds.add(borrowId)
                        } else {
                            // Don't falsely reclaim screen if the receiver borrowed more
                            // IDs than we can safely retain. New session clears the flag.
                            resource.borrowOverflow = true
                        }
                    }
                }
                4L -> {
                    val borrowId = boundedBorrowId(item["borrowID"]) ?: return@forEach
                    resource.borrowIds.remove(borrowId)
                    // Overflow cannot be safely reconciled from incomplete identifiers.
                }''', '''                3L -> {
                    if (!item.containsKey("borrowID")) {
                        // The real B233 changeModes lacks borrowID entirely; this
                        // is legitimate and a later ID-less UNBORROW matches by count.
                        // Do not treat an explicit *invalid* borrowID as anonymous.
                        if (resource.anonymousBorrowCount < MAX_ACTIVE_BORROWS) {
                            resource.anonymousBorrowCount++
                        } else {
                            resource.borrowOverflow = true // fail closed on overflow
                        }
                    } else {
                        val borrowId = boundedBorrowId(item["borrowID"])
                        if (borrowId == null) {
                            resource.borrowOverflow = true
                            return@forEach
                        }
                        if (borrowId !in resource.borrowIds) {
                            if (resource.borrowIds.size < MAX_ACTIVE_BORROWS) {
                                resource.borrowIds.add(borrowId)
                            } else {
                                resource.borrowOverflow = true
                            }
                        }
                    }
                }
                4L -> {
                    if (!item.containsKey("borrowID")) {
                        if (resource.anonymousBorrowCount > 0) resource.anonymousBorrowCount--
                    } else {
                        val borrowId = boundedBorrowId(item["borrowID"]) ?: return@forEach
                        resource.borrowIds.remove(borrowId)
                    }
                    // Overflow cannot be repaired by an incomplete trace; reset
                    // only with a fresh AirPlay session generation.
                }''', 'borrow_lifecycle')
    s = replace_once(s, '''        if (!screen.borrowOverflow && screen.borrowIds.isEmpty() &&''', '''        if (!screen.borrowOverflow && screen.anonymousBorrowCount == 0 && screen.borrowIds.isEmpty() &&''', 'screen_gate')
    s = replace_once(s, '''        if (audio.borrowOverflow || audio.borrowIds.isNotEmpty()) return false''', '''        if (audio.borrowOverflow || audio.anonymousBorrowCount > 0 || audio.borrowIds.isNotEmpty()) return false''', 'audio_gate')
    return s


CASES = '''

/** Real B233 trace: resourceID=1 BORROW(type=3) and UNBORROW(type=4)
 * carry no borrowID, and must not permanently strand screen ownership.
 * CatPlay resource_manager.rs tracks a bounded borrow count. */
class B233AnonymousBorrowTraceTest {
    private fun event(resourceId: Long, kind: Long): Map<String, Any> =
        mapOf("resources" to listOf(linkedMapOf<String, Any>(
            "resourceID" to resourceId, "transferType" to kind,
        ).apply {
            if (kind == 3L) {
                put("transferPriority", 500L)
                put("unborrowConstraint", 1000L)
            }
        }))

    @Test fun actualB233ScreenBorrowThenUnborrowRestoresController() {
        val state = B233ResourceState()
        state.initialize(null)
        state.applyCarChange(event(1L, 3L))
        assertFalse(state.screenOwnedByPhone())
        assertEquals(2L, state.resourcesForModesChanged(false).single()["entity"])
        assertEquals(1L, state.resourcesForModesChanged(false).single()["permanentEntity"])
        state.applyCarChange(event(1L, 4L))
        assertTrue(state.screenOwnedByPhone())
        assertEquals(1L, state.resourcesForModesChanged(false).single()["entity"])
    }

    @Test fun nestedAnonymousBorrowReleasesOnlyAfterMatchingCount() {
        val state = B233ResourceState()
        state.initialize(null)
        state.applyCarChange(event(1L, 3L))
        state.applyCarChange(event(1L, 3L))
        state.applyCarChange(event(1L, 4L))
        state.requestPhoneScreen()
        assertFalse(state.screenOwnedByPhone())
        state.applyCarChange(event(1L, 4L))
        assertTrue(state.screenOwnedByPhone())
    }

    @Test fun namedAndAnonymousBorrowMustBothBeReleased() {
        val state = B233ResourceState()
        state.initialize(null)
        state.applyCarChange(event(1L, 3L))
        state.applyCarChange(mapOf("resources" to listOf(mapOf(
            "resourceID" to 1L, "transferType" to 3L, "borrowID" to "named-1"))))
        state.applyCarChange(event(1L, 4L))
        assertFalse(state.screenOwnedByPhone())
        state.applyCarChange(mapOf("resources" to listOf(mapOf(
            "resourceID" to 1L, "transferType" to 4L, "borrowID" to "named-1"))))
        assertTrue(state.screenOwnedByPhone())
    }

    @Test fun audioAnonymousBorrowPreventsPrematureExplicitAudioSetup() {
        val state = B233ResourceState()
        state.initialize(null)
        state.applyCarChange(event(2L, 3L))
        assertFalse(state.requestMainAudioForSetup())
        state.applyCarChange(event(2L, 4L))
        assertTrue(state.requestMainAudioForSetup())
    }

    @Test fun unmatchedUnborrowCannotClearNamedOrOverflow() {
        val state = B233ResourceState()
        state.initialize(null)
        state.applyCarChange(mapOf("resources" to listOf(mapOf(
            "resourceID" to 1L, "transferType" to 3L, "borrowID" to "named"))))
        state.applyCarChange(event(1L, 4L))
        assertFalse(state.screenOwnedByPhone())
        state.initialize(null)
        for (i in 0..32) state.applyCarChange(event(1L, 3L))
        for (i in 0..32) state.applyCarChange(event(1L, 4L))
        assertFalse(state.screenOwnedByPhone())
        state.initialize(null)
        assertTrue(state.screenOwnedByPhone())
    }

    @Test fun explicitEmptyBorrowIdRemainsFailClosed() {
        val state = B233ResourceState()
        state.initialize(null)
        state.applyCarChange(mapOf("resources" to listOf(mapOf(
            "resourceID" to 1L, "transferType" to 3L, "borrowID" to ""))))
        state.applyCarChange(event(1L, 4L))
        assertFalse(state.screenOwnedByPhone())
    }
}
'''


def main():
    p=argparse.ArgumentParser()
    p.add_argument('repo')
    p.add_argument('--apply',action='store_true')
    args=p.parse_args()
    root=Path(args.repo)
    source=root/STATE; test=root/TEST
    if not source.is_file() or not test.is_file(): raise SystemExit('STOP_T106_MISSING_T104_B233_BASE')
    new_state=patch_state(source.read_text())
    original_test=test.read_text()
    if 'class B233AnonymousBorrowTraceTest' in original_test: raise SystemExit('STOP_T106_ALREADY_APPLIED')
    if args.apply:
        source.write_text(new_state)
        test.write_text(original_test+CASES)
    print('T106_B233_ANONYMOUS_BORROW_PREFLIGHT=PASS')
    print('T106_CHANGED_FILES=2')
    print('T106_APPLIED='+str(args.apply))

if __name__=='__main__':
    main()