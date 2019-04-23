package org.runestar.cs2.dfa

import org.runestar.cs2.ir.Element
import org.runestar.cs2.ir.Expression
import org.runestar.cs2.ir.Func
import org.runestar.cs2.ir.Instruction
import org.runestar.cs2.ir.list

internal object DeleteNops : Phase {

    override fun transform(func: Func) {
        val itr = func.instructions.iterator()
        for (insn in itr) {
            if (insn !is Instruction.Assignment) continue
            val defs = insn.definitions.list<Element.Variable>()
            if (defs.isNotEmpty()) continue
            val e = insn.expression.list<Expression>().singleOrNull() ?: continue
            if (e !is Element.Variable.Stack) continue
            itr.remove()
        }
    }
}