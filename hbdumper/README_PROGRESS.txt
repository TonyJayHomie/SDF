HB DUMPER - TURN SNAPSHOT 11 (REAL AUDIT, NO STUBS)
===================================================

Bug hunt this turn (no stubs, real openocd binary, real Worker instances)
-------------------------------------------------------------------------
1. SILENT-FAILURE bug: start_serial_capture would log an error then return
   None, and action_dump would proceed to OpenOCD anyway. User would
   then see "no HBDP frame captured" instead of "your COM port is wrong".
   FIX: start_serial_capture now returns bool, action_dump aborts with
   clear status if serial port cannot be opened.

2. SILENT-FAILURE bug: three "except Exception: pass" patterns swallowed
   errors with no log entry. FIX: every one now logs the exception.

Real audit (tests/real_audit.py, no stubs) - all 27 checks pass
---------------------------------------------------------------
- Module imports
- All 5 bundled assets present (payloads, openocd scripts, firmware,
  zadig)
- All 6 OpenOCD command builders produce correct argv shape
- ACTUAL openocd 0.12.0 binary spawns and reports the expected error
  without hardware ("Error: open failed")
- HBDP frame parser: valid frame, truncated frame (->None), bad CRC
  (returns payload + warning), bad trailer (->None)
- 25 RoboDurden firmware binaries present, including 2.1.1 variants
- Safety gates (called on real Worker, no stubs):
    * erase aborts without backup
    * erase aborts with wrong-size backup
    * flash aborts without backup
- AST walk of Worker class: never touches Tk variables directly
  (no status_var.set, no log_text.insert) -> thread-safe via queues
- No shell=True anywhere
- Zero silent "except: pass" blocks remain

HBDumper.exe
------------
- PE32+ Windows x86-64 GUI binary, 9.9 MB
- Built with PyInstaller 6.20.0 under Wine 10.0
- Python 3.11.9, pyserial bundled

