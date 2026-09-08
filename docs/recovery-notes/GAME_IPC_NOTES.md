# Creatures Map Editor 1.08 — live-game IPC and Open From Game

## Original `ClientSide` source match

This is stronger than a normal semantic cross-reference.

The functions in MapEditor.exe at `00413830..00413E10` match the released
Creature Labs `common/clientside.cpp` implementation directly, including
member order, Win32 calls, timeout, named-object formats and transaction
lifetime.

Original source paths:

```text
webc2e/emscripten-c2e/common/clientside.h
webc2e/emscripten-c2e/common/clientside.cpp
```

The same shared-memory layout is independently documented by the `pyc2e`
Windows interface.

## `C2ETransferHeader`

Exact layout:

```text
C2ETransferHeader [0x18]

+00 char MagicCookie[4]   "c2e@"
+04 DWORD ServerProcessId
+08 int ReturnCode
+0C uint DataSize
+10 uint BufferSize
+14 int Pad

+18 byte Data[]
```

There is an old source-header comment which says `"c2e!"`; that comment is
stale. The original implementation, MapEditor binary, and later independent
clients all test/use:

```text
c2e@
```

## `C2EClientSide`

Exact original class layout:

```text
C2EClientSide [0x14]

+00 HANDLE myMutex
+04 HANDLE myRequestEvent
+08 HANDLE myResultEvent
+0C HANDLE myMappedFile
+10 C2ETransferHeader *mySharedMem
```

Recovered methods:

```text
00413830 C2EClientSide_ctor
00413850 C2EClientSide_Cleanup
004138B0 C2EClientSide_Open

00413B50 C2EClientSide_GetBufferSize

00413CF0 C2EClientSide_StartTransaction
00413DE0 C2EClientSide_GetResultSize
00413DF0 C2EClientSide_GetResultBuffer
00413E00 C2EClientSide_GetReturnCode
00413E10 C2EClientSide_EndTransaction
```

## Named Win32 IPC objects

For server name `X`:

```text
X_mutex
X_request
X_result
X_mem
```

`ClientSide::Open`:

1. opens the named mutex;
2. opens request/result events;
3. opens file mapping;
4. maps it;
5. requires shared memory to start with `c2e@`.

## Transaction protocol

`StartTransaction(data,size)`:

```text
if size > BufferSize:
    fail

if MagicCookie != "c2e@":
    fail

WaitForSingleObject(myMutex, 500ms)

copy request to shared + 0x18
DataSize = request size

ResetEvent(myResultEvent)
SetEvent(myRequestEvent)

serverProcess = OpenProcess(ServerProcessId)

WaitForMultipleObjects(
    result event,
    server process,
    wait-any,
    infinite)
```

Waiting on both is deliberate: a client does not hang forever if the engine
process dies instead of returning a result.

On a successful result the mutex remains owned.

Caller then reads:

```text
ReturnCode
DataSize
Data at +0x18
```

and finally:

```text
EndTransaction()
    ReleaseMutex(myMutex)
```

## `C2ECAOSOutput`

The Map Editor wraps the IPC client in a dual-purpose output object:

```text
C2ECAOSOutput [0x1C]

+00 C2EClientSide client
+14 VC6CString outputPath
+18 bool injectToGame
+19 padding
```

This explains a previously awkward constructor:

### Inject

The Inject handlers construct it with:

```text
outputPath = "game.log"
injectToGame = true
```

### Export

The Export handlers construct it with:

```text
outputPath = "!world.cos" or "!addon.cos"
injectToGame = false
```

Thus the same CAOS generator can either execute against a running game or
produce a `.cos` file.

Recovered:

```text
00414790 C2ECAOSOutput_ctor
004148A0 C2ECAOSOutput_dtor
00414900 C2ECAOSOutput_FormatAndRun
00414980 C2ECAOSOutput_Run
```

`C2ECAOSOutput_FormatAndRun` is the printf-style format/run wrapper used
extensively by map/room query and generation code. It is now named and has the
same `C2ECAOSOutput *` ECX receiver as the rest of the API. Its variadic stack
tail remains deliberately untyped: the wrapper's role is evidenced, but a
fixed formal signature would be less accurate than retaining Ghidra's inferred
ordinary arguments.

## Open From Game

The application handler eventually reaches:

```text
0040B240 CC2ERoomEditorDoc_OpenFromGame
```

Flow:

```text
Document_OpenFromGame
    |
    +--> C2EWorldModel_ReadFromGame
    |       |
    |       +--> C2EEditorMetaRoom_ReadFromGame
    |               |
    |               +--> C2EEditorRoom_ReadFromGame
    |
    +--> read all 16 x 20 CA RATE values
```

### World queries

```text
outv mapw outs " " outv maph
outs emid
outv door <room1> <room2> outs " "
```

The world loader reconstructs map dimensions, metaroom IDs and door
permeabilities.

### Metaroom queries

```text
outs mloc <metaRoomId>
outs mmsc <centreX> <centreY>
outs bkds <metaRoomId>
outs erid <metaRoomId>
```

These reconstruct bounds, music, backgrounds and room IDs.

### Room query

```text
outv rtyp <roomId> outs " " outs rloc <roomId>
```

The response is parsed into Room Type plus the six canonical geometry
coordinates already recovered in `C2ERoomGeometry`.

### CA rate queries

The document then loops:

```text
room type 0..15
CA index  0..19
```

and executes:

```text
outs rate <roomType> <caIndex>
```

The three returned floats become:

```text
gain
loss
diffusion
```

in the document CA-rate matrix.

This means the editor's two directions now meet:

```text
Editor model -> generated CAOS -> running C2e
running C2e -> CAOS queries -> editor model
```
