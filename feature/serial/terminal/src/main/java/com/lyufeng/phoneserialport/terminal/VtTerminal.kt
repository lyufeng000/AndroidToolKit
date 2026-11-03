package com.lyufeng.phoneserialport.terminal

data class Cell(val char: Char = ' ', val foreground: Int = 7, val background: Int = 0, val bold: Boolean = false, val underline: Boolean = false, val inverse: Boolean = false)
data class TerminalSnapshot(val rows: List<List<Cell>>, val cursorRow: Int, val cursorColumn: Int)

/** Incremental ANSI/VT100 screen. Escape state survives USB packet boundaries. */
class VtTerminal(var columns: Int = 80, var rowCount: Int = 24) {
    private var screen = Array(rowCount) { Array(columns) { Cell() } }
    private var row = 0
    private var col = 0
    private var savedRow = 0
    private var savedCol = 0
    private var top = 0
    private var bottom = rowCount - 1
    private var style = Cell()
    private var savedStyle = Cell()
    private var state = 0
    private val sequence = StringBuilder()
    private var wrap = true
    private var wrapPending = false
    private var origin = false
    var cursorKeysApplication = false
        private set
    @Synchronized fun resize(cols: Int, rows: Int) {
        require(cols in 20..240 && rows in 5..120)
        val old = screen
        screen = Array(rows) { y -> Array(cols) { x -> old.getOrNull(y)?.getOrNull(x) ?: Cell() } }
        columns = cols; rowCount = rows; row = row.coerceIn(0,rows-1); col = col.coerceIn(0,cols-1)
        top = 0; bottom = rows-1; wrapPending = false
    }
    @Synchronized fun snapshot() = TerminalSnapshot(screen.map { it.toList() }, row, col)
    @Synchronized fun resetInputState() {
        state=0;sequence.clear()
    }
    fun fork(): VtTerminal = synchronized(this) {VtTerminal(columns,rowCount).also(::copyStateTo)}
    fun restoreFrom(source: VtTerminal) {
        val stable=source.fork()
        synchronized(this) {stable.copyStateTo(this)}
    }
    private fun copyStateTo(target: VtTerminal) {
        target.columns=columns;target.rowCount=rowCount
        target.screen=Array(rowCount) {screen[it].copyOf()}
        target.row=row;target.col=col;target.savedRow=savedRow;target.savedCol=savedCol
        target.top=top;target.bottom=bottom;target.style=style;target.savedStyle=savedStyle
        target.state=state;target.sequence.clear();target.sequence.append(sequence)
        target.wrap=wrap;target.wrapPending=wrapPending;target.origin=origin
        target.cursorKeysApplication=cursorKeysApplication
    }
    @Synchronized fun reset() {
        screen = Array(rowCount) { Array(columns) { Cell() } }; row = 0; col = 0
        top = 0; bottom = rowCount-1; style = Cell(); state = 0; sequence.clear(); wrapPending = false; origin = false
        savedRow = 0; savedCol = 0; savedStyle = Cell(); wrap = true; cursorKeysApplication = false
    }
    @Synchronized fun feed(text: String) {
        for(c in text) when(state) {
            0 -> when(c) {
                '\u001b' -> state = 1
                '\r' -> { col = 0; wrapPending = false }
                '\n', '\u000b', '\u000c' -> { index(); wrapPending = false }
                '\b' -> { col = (col-1).coerceAtLeast(0); wrapPending = false }
                '\t' -> { col = ((col/8+1)*8).coerceAtMost(columns-1); wrapPending = false }
                else -> if(c.code >= 32 && c.code != 127) {
                    if(wrapPending) { col = 0; index(); wrapPending = false }
                    screen[row][col] = style.copy(char = c)
                    if(col == columns-1) wrapPending = wrap else col++
                }
            }
            1 -> { state = 0; when(c) {
                '[' -> { state = 2; sequence.clear() }
                ']' -> state = 3
                '7' -> save()
                '8' -> restore()
                'D' -> index()
                'E' -> { col = 0; index() }
                'M' -> reverseIndex()
                'c' -> reset()
                '(', ')' -> state = 5 // Consume the character-set designator.
            } }
            2 -> if(c in '@'..'~') { execute(c,sequence.toString()); state = 0 }
                else if(sequence.length < 128) sequence.append(c) else { sequence.clear(); state = 0 }
            3 -> when(c) { '\u0007' -> state = 0; '\u001b' -> state = 4 }
            4 -> { state = if(c == '\\') 0 else 3 }
            5 -> state = 0
        }
    }
    private fun save() { savedRow = row; savedCol = col; savedStyle = style }
    private fun restore() { row = savedRow.coerceIn(0,rowCount-1); col = savedCol.coerceIn(0,columns-1); style = savedStyle; wrapPending = false }
    private fun blank() = Array(columns) { style.copy(char = ' ') }
    private fun index() {
        if(row == bottom) { for(y in top until bottom) screen[y] = screen[y+1]; screen[bottom] = blank() }
        else row = (row+1).coerceAtMost(rowCount-1)
    }
    private fun reverseIndex() {
        if(row == top) { for(y in bottom downTo top+1) screen[y] = screen[y-1]; screen[top] = blank() }
        else row = (row-1).coerceAtLeast(0)
    }
    private fun eraseLine(y: Int, from: Int, to: Int) { for(x in from.coerceAtLeast(0)..to.coerceAtMost(columns-1)) screen[y][x] = style.copy(char=' ') }
    private fun execute(command: Char, raw: String) {
        val privateMode = raw.startsWith('?')
        val arguments = raw.removePrefix("?")
        if(arguments.any { it !in '0'..'9' && it != ';' }) return
        val p = arguments.split(';').map { if(it.isEmpty()) 0 else it.toIntOrNull() ?: Int.MAX_VALUE }
        // A screen operation never needs a distance larger than the screen. Clamp before arithmetic.
        fun n(i: Int = 0, default: Int = 1) = p.getOrElse(i) { default }
            .let { if(it == 0) default else it }.coerceAtMost(maxOf(columns,rowCount))
        val minimum = if(origin) top else 0
        val maximum = if(origin) bottom else rowCount-1
        if(command != 'm') wrapPending = false
        when(command) {
            'A' -> row = (row-n()).coerceAtLeast(minimum)
            'B','e' -> row = (row+n()).coerceAtMost(maximum)
            'C','a' -> col = (col+n()).coerceAtMost(columns-1)
            'D' -> col = (col-n()).coerceAtLeast(0)
            'E' -> { row = (row+n()).coerceAtMost(maximum); col = 0 }
            'F' -> { row = (row-n()).coerceAtLeast(minimum); col = 0 }
            'G','`' -> col = (n()-1).coerceIn(0,columns-1)
            'd' -> row = (n()-1+minimum).coerceIn(minimum,maximum)
            'H','f' -> { row = (n()-1+minimum).coerceIn(minimum,maximum); col = (n(1)-1).coerceIn(0,columns-1) }
            'J' -> when(p[0]) {
                0 -> { eraseLine(row,col,columns-1); for(y in row+1 until rowCount) eraseLine(y,0,columns-1) }
                1 -> { for(y in 0 until row) eraseLine(y,0,columns-1); eraseLine(row,0,col) }
                2,3 -> for(y in 0 until rowCount) eraseLine(y,0,columns-1)
            }
            'K' -> when(p[0]) { 0 -> eraseLine(row,col,columns-1); 1 -> eraseLine(row,0,col); 2 -> eraseLine(row,0,columns-1) }
            'r' -> { val a = n()-1; val b = n(1,rowCount)-1; if(a in 0 until b && b < rowCount) { top = a; bottom = b; row = if(origin) top else 0; col = 0 } }
            's' -> save()
            'u' -> restore()
            'S' -> repeat(n().coerceAtMost(rowCount)) { for(y in top until bottom) screen[y] = screen[y+1]; screen[bottom] = blank() }
            'T' -> repeat(n().coerceAtMost(rowCount)) { for(y in bottom downTo top+1) screen[y] = screen[y-1]; screen[top] = blank() }
            'L' -> if(row in top..bottom) repeat(n().coerceAtMost(rowCount)) { for(y in bottom downTo row+1) screen[y] = screen[y-1]; screen[row] = blank() }
            'M' -> if(row in top..bottom) repeat(n().coerceAtMost(rowCount)) { for(y in row until bottom) screen[y] = screen[y+1]; screen[bottom] = blank() }
            'P' -> { val count = n().coerceAtMost(columns-col); for(x in col until columns-count) screen[row][x] = screen[row][x+count]; eraseLine(row,columns-count,columns-1) }
            '@' -> { val count = n().coerceAtMost(columns-col); for(x in columns-1 downTo col+count) screen[row][x] = screen[row][x-count]; eraseLine(row,col,col+count-1) }
            'X' -> eraseLine(row,col,col+n()-1)
            'h','l' -> if(privateMode) p.forEach { when(it) { 1 -> cursorKeysApplication = command == 'h'; 6 -> { origin = command == 'h'; row = if(origin) top else 0; col = 0 }; 7 -> wrap = command == 'h' } }
            'm' -> p.forEach { when(it) {
                0 -> style = Cell(); 1 -> style = style.copy(bold=true); 4 -> style = style.copy(underline=true); 7 -> style = style.copy(inverse=true)
                22 -> style = style.copy(bold=false); 24 -> style = style.copy(underline=false); 27 -> style = style.copy(inverse=false)
                in 30..37 -> style = style.copy(foreground=it-30); in 40..47 -> style = style.copy(background=it-40)
                39 -> style = style.copy(foreground=7); 49 -> style = style.copy(background=0)
                in 90..97 -> style = style.copy(foreground=it-90+8); in 100..107 -> style = style.copy(background=it-100+8)
            } }
        }
    }
}

class SimpleTerminalFilter {
    private var state = 0
    private val escape = StringBuilder()
    fun feed(text: String): String = buildString {
        for(c in text) when(state) {
            0 -> when(c) { '\u001b' -> state=1; '\u000c' -> append('\u000c'); else -> append(c) }
            1 -> { state = if(c == '[') 2 else 0; escape.clear() }
            2 -> if(c in '@'..'~') { if(c == 'J') append('\u000c'); state=0 } else if(escape.length < 128) escape.append(c) else state=0
        }
    }
}
