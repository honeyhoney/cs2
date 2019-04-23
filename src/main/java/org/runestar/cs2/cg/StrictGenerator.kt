package org.runestar.cs2.cg

import org.runestar.cs2.Loader
import org.runestar.cs2.Opcodes.*
import org.runestar.cs2.Type
import org.runestar.cs2.cfa.Construct
import org.runestar.cs2.ir.Element
import org.runestar.cs2.ir.Expression
import org.runestar.cs2.ir.Func
import org.runestar.cs2.ir.Instruction
import org.runestar.cs2.ir.list
import org.runestar.cs2.names
import org.runestar.cs2.util.strip

class StrictGenerator(
        private val graphicNameLoader: Loader<String> = Loader.GRAPHIC_NAMES,
        private val scriptNameLoader: Loader<String> = Loader.SCRIPT_NAMES,
        private val statNameLoader: Loader<String> = Loader.STAT_NAMES,
        private val objNameLoader: Loader<String> = Loader.OBJ_NAMES,
        private val invNameLoader: Loader<String> = Loader.INV_NAMES,
        private val mapAreaNameLoader: Loader<String> = Loader.MAPAREA_NAMES,
        private val paramNameLoader: Loader<String> = Loader.PARAM_NAMES
) : Generator {

    override fun write(appendable: StringBuilder, func: Func, root: Construct) {
        State(appendable, func, root).write()
    }

    private inner class State(appendable: StringBuilder, private val func: Func, private val root: Construct) {

        private var inCalc = false

        private var endingLine = true

        private val definedVars = HashSet<Element.Variable.Local>(func.arguments)

        private val writer = LineWriter(appendable)

        internal fun write() {
            writer.append("// ").append(func.id)
            writer.nextLine()
            val scriptName = scriptNameLoader.load(func.id)
            if (scriptName == null) {
                writer.append("script").append(func.id)
            } else {
                writer.append(scriptName)
            }
            if (func.arguments.isNotEmpty() || func.returnTypes.isNotEmpty()) {
                writer.append('(')
                func.arguments.joinTo(writer) { "${it.type.typeLiteral} \$${it.type.nameLiteral}${it.id}" }
                writer.append(')')
            }
            if (func.returnTypes.isNotEmpty()) {
                writer.append('(')
                func.returnTypes.joinTo(writer) { it.typeLiteral }
                writer.append(')')
            }
            writeConstruct(root)
            if (endingLine) writer.nextLine()
        }

        private fun writeConstruct(construct: Construct) {
            when (construct) {
                is Construct.Seq -> writeSeq(construct)
                is Construct.If -> writeIf(construct)
                is Construct.While -> writeWhile(construct)
                is Construct.Switch -> writeSwitch(construct)
            }
        }

        private fun writeSeq(construct: Construct.Seq) {
            for (insn in construct.insns) {
                writer.nextLine()
                writeInsn(insn)
            }
            construct.next?.let { writeConstruct(it) }
        }

        private fun writeIf(construct: Construct.If) {
            writer.nextLine()
            val if0 = construct.branches.first()
            writer.append("if ")
            writeExpr(if0.condition)
            writer.append(" {")
            writer.indents++
            writeConstruct(if0.construct)
            writer.indents--
            writer.nextLine()
            writer.append('}')
            for (ifn in construct.branches.drop(1)) {
                writer.append(" else if ")
                writeExpr(ifn.condition)
                writer.append(" {")
                writer.indents++
                writeConstruct(ifn.construct)
                writer.indents--
                writer.nextLine()
                writer.append('}')
            }
            val elze = construct.elze
            if (elze != null) {
                writer.append(" else {")
                writer.indents++
                writeConstruct(elze)
                writer.indents--
                writer.nextLine()
                writer.append('}')
            }
            construct.next?.let { writeConstruct(it) }
        }

        private fun writeWhile(construct: Construct.While) {
            writer.nextLine()
            writer.append("while ")
            writeExpr(construct.condition)
            writer.append(" {")
            writer.indents++
            writeConstruct(construct.inside)
            writer.indents--
            writer.nextLine()
            writer.append('}')
            construct.next?.let { writeConstruct(it) }
        }

        private fun writeSwitch(construct: Construct.Switch) {
            writer.nextLine()
            val type = construct.expr.types.single()
            writer.append("switch_").append(type.typeLiteral).append(" (")
            writeExpr(construct.expr)
            writer.append(") {")
            for ((ns, con) in construct.map) {
                writer.indents++
                writer.nextLine()
                val itr = ns.iterator()
                writer.append("case ")
                writeConstantInt(itr.next(), type)
                while (itr.hasNext()) {
                    writer.append(", ")
                    writeConstantInt(itr.next(), type)
                }
                writer.append(" :")
                writer.indents++
                writeConstruct(con)
                writer.indents--
                writer.indents--
            }
            val elze = construct.elze
            if (elze != null) {
                writer.indents++
                writer.nextLine()
                writer.append("case default :")
                writer.indents++
                writeConstruct(elze)
                writer.indents--
                writer.indents--
            }
            writer.nextLine()
            writer.append('}')
            construct.next?.let { writeConstruct(it) }
        }

        private fun writeInsn(insn: Instruction) {
            when (insn) {
                is Instruction.Assignment -> writeAssignment(insn)
                is Instruction.Return -> {
                    if (writer.indents == 0 && func.returnTypes.isEmpty()) {
                        endingLine = false
                        return
                    } else {
                        writer.append("return")
                        val es = insn.expression.list<Expression>()
                        if (es.isNotEmpty()) {
                            writer.append('(')
                            writeExprList(es)
                            writer.append(')')
                        }
                    }
                }
            }
            writer.append(';')
        }

        private fun writeAssignment(insn: Instruction.Assignment) {
            val defs = insn.definitions.list<Element.Variable>()
            if (defs.isNotEmpty()) {
                if (defs.size == 1) {
                    val def = defs.single()
                    if (def is Element.Variable.Local && definedVars.add(def)) {
                        writer.append("def_")
                        writer.append(def.type.typeLiteral)
                        writer.append(' ')
                    }
                    writeVar(def)
                } else {
                    writeExprList(defs)
                }
                writer.append(" = ")
            }
            writeExpr(insn.expression)
        }

        private fun writeExpr(expr: Expression) {
            when (expr) {
                is Element.Variable -> writeVar(expr)
                is Element.Constant -> writeConst(expr)
                is Expression.Operation -> writeOperation(expr)
                is Expression.Compound -> writeExprList(expr.expressions)
            }
        }

        private fun writeVar(v: Element.Variable) {
            when (v) {
                is Element.Variable.Local -> writer.append('$').append(v.type.nameLiteral).append(v.id)
                is Element.Variable.Varp -> writer.append("%var").append(v.id)
                is Element.Variable.Varbit -> writer.append("%varbit").append(v.id)
                is Element.Variable.Varc -> writer.append("%varc").append(v.type.topType.nameLiteral).append(v.id)
                else -> error(v)
            }
        }

        private fun writeConst(expr: Element.Constant) {
            when (expr.type == Type.STRING) {
                false -> writeConstantInt(expr.value as Int, expr.type)
                true -> writeConstantString(expr.value as String)
            }
        }

        private fun writeConstantInt(n: Int, type: Type) {
            when (type) {
                Type.TYPE -> writer.append(Type.of(n).nameLiteral)
                Type.COMPONENT -> {
                    when (n) {
                        -1 -> writer.append(null)
                        -2147483645 -> writer.append("event_com")
                        -2147483642 -> writer.append("event_dragtarget")
                        else -> writer.append(n ushr 16).append(':').append(n and 0xFFFF)
                    }
                }
                Type.BOOLEAN -> when (n) {
                    0 -> writer.append(false)
                    1 -> writer.append(true)
                    -1 -> writer.append(null)
                    else -> error(n)
                }
                Type.COORD -> when (n) {
                    -1 -> writer.append(null)
                    else -> {
                        val plane = n ushr 28
                        val x = (n ushr 14) and 0x3FFF
                        val y = n and 0x3FFF
                        writer.append(plane).append('_')
                        writer.append((x / 64)).append('_')
                        writer.append((y / 64)).append('_')
                        writer.append((x and 0x3F)).append('_')
                        writer.append((y and 0x3F))
                    }
                }
                Type.GRAPHIC -> writeQuoteNamedInt(graphicNameLoader, n)
                Type.FONTMETRICS -> writeNamedInt(graphicNameLoader, n)
                Type.COLOUR -> {
                    when (n) {
                        0xFF0000 -> writer.append("^red")
                        0x00FF00 -> writer.append("^green")
                        0x0000FF -> writer.append("^blue")
                        0xFFFF00 -> writer.append("^yellow")
                        0xFF00FF -> writer.append("^magenta")
                        0x00FFFF -> writer.append("^cyan")
                        0xFFFFFF -> writer.append("^white")
                        0x000000 -> writer.append("^black")
                        else -> writer.append("0x").append(n.toString(16).padStart(6, '0'))
                    }
                }
                Type.INT -> {
                    when (n) {
                        Int.MAX_VALUE -> writer.append("^max_32bit_int")
                        Int.MIN_VALUE -> writer.append("^min_32bit_int")
                        -2147483647 -> writer.append("event_mousex")
                        -2147483646 -> writer.append("event_mousey")
                        -2147483644 -> writer.append("event_opindex")
                        -2147483643 -> writer.append("event_comid")
                        -2147483641 -> writer.append("event_dragtargetid")
                        -2147483640 -> writer.append("event_keytyped")
                        else -> writer.append(n)
                    }
                }
                Type.CHAR -> {
                    when (n) {
                        -2147483639 -> writer.append("event_keypressed")
                        -1 -> writer.append(null)
                        else -> error(n)
                    }
                }
                Type.STAT -> writeNamedInt(statNameLoader, n)
                Type.OBJ, Type.NAMEDOBJ -> writeNamedInt(objNameLoader, n)
                Type.IFTYPE -> {
                    val s = when (n) {
                        3 -> "rectangle"
                        4 -> "text"
                        5 -> "graphic"
                        6 -> "model"
                        9 -> "line"
                        else -> error(n)
                    }
                    writer.append("^iftype_").append(s)
                }
                Type.SETSIZE -> {
                    val s = when (n) {
                        0 -> "abs"
                        1 -> "minus"
                        2 -> "2"
                        else -> error(n)
                    }
                    writer.append("^setsize_").append(s)
                }
                Type.SETPOSH -> {
                    val s = when (n) {
                        0 -> "abs_left"
                        1 -> "abs_centre"
                        2 -> "abs_right"
                        3 -> "3"
                        4 -> "4"
                        5 -> "5"
                        else -> error(n)
                    }
                    writer.append("^setpos_").append(s)
                }
                Type.SETPOSV -> {
                    val s = when (n) {
                        0 -> "abs_top"
                        1 -> "abs_centre"
                        2 -> "abs_bottom"
                        3 -> "3"
                        4 -> "4"
                        5 -> "5"
                        else -> error(n)
                    }
                    writer.append("^setpos_").append(s)
                }
                Type.SETTEXTALIGNH -> {
                    val s = when (n) {
                        0 -> "left"
                        1 -> "centre"
                        2 -> "right"
                        else -> error(n)
                    }
                    writer.append("^settextalign_").append(s)
                }
                Type.SETTEXTALIGNV -> {
                    val s = when (n) {
                        0 -> "top"
                        1 -> "centre"
                        2 -> "bottom"
                        else -> error(n)
                    }
                    writer.append("^settextalign_").append(s)
                }
                Type.VAR -> writer.append("var").append(n)
                Type.INV -> writeNamedInt(invNameLoader, n)
                Type.MAPAREA -> writeNamedInt(mapAreaNameLoader, n)
                Type.CHATTYPE -> {
                    val s = when(n) {
                        0 -> "gamemessage"
                        1 -> "modchat"
                        2 -> "publicchat"
                        3 -> "privatechat"
                        4 -> "engine"
                        5 -> "loginlogoutnotification"
                        6 -> "privatechatout"
                        7 -> "modprivatechat"
                        9 -> "friendschat"
                        11 -> "friendschatnotification"
                        14 -> "broadcast"
                        26 -> "snapshotfeedback"
                        27 -> "obj_examine"
                        28 -> "npc_examine"
                        29 -> "loc_examine"
                        30 -> "friendnotification"
                        31 -> "ignorenotification"
                        90 -> "autotyper"
                        91 -> "modautotyper"
                        99 -> "console"
                        101 -> "tradereq"
                        102 -> "trade"
                        103 -> "chalreq_trade"
                        104 -> "chalreq_friendschat"
                        105 -> "spam"
                        106 -> "playerrelated"
                        107 -> "10sectimeout"
                        108 -> "108"
                        else -> error(n)
                    }
                    writer.append("^chattype_").append(s)
                }
                Type.PARAM -> writeNamedInt(paramNameLoader, n)
                Type.BIT -> {
                    val s = when (n) {
                        0 -> "^false"
                        1 -> "^true"
                        else -> error(n)
                    }
                    writer.append(s)
                }
                else -> {
                    when (n) {
                        -1 -> writer.append(null)
                        else -> writer.append(n)
                    }
                }
            }
        }

        private fun writeQuoteNamedInt(nameLoader: Loader<String>, n: Int) {
            if (n == -1) {
                writer.append(null)
                return
            }
            val name = nameLoader.load(n)
            if (name == null) {
                writer.append(n)
            } else {
                writer.append("\"").append(name).append('"')
            }
        }

        private fun writeNamedInt(nameLoader: Loader<String>, n: Int) {
            if (n == -1) {
                writer.append(null)
                return
            }
            val name = nameLoader.load(n)
            if (name == null) {
                writer.append(n)
            } else {
                writer.append(name)
            }
        }

        private fun writeConstantString(s: String) {
            when (s) {
                "event_opbase" -> writer.append(s)
                else -> writer.append('"').append(s).append('"')
            }
        }

        private fun writeOperation(expr: Expression.Operation) {
            val args = expr.arguments.list<Expression>()
            val op = expr.id
            when (op) {
                INVOKE -> {
                    writeInvoke(expr)
                    return
                }
                DEFINE_ARRAY -> {
                    writer.append("def_")
                    writeExpr(args[1])
                    writer.append(" \$array")
                    writeExpr(args[0])
                    writer.append('(')
                    writeExpr(args[2])
                    writer.append(')')
                    return
                }
                GET_ARRAY_INT -> {
                    writer.append("\$array")
                    writeExpr(args[0])
                    writer.append('(')
                    writeExpr(args[1])
                    writer.append(')')
                    return
                }
                SET_ARRAY_INT -> {
                    writer.append("\$array")
                    writeExpr(args[0])
                    writer.append('(')
                    writeExpr(args[1])
                    writer.append(") = ")
                    writeExpr(args[2])
                    return
                }
                JOIN_STRING -> {
                    writer.append('"')
                    for (a in args) {
                        if (a is Element.Constant && a.value is String) {
                            writer.append(a.value)
                        } else {
                            writer.append('<')
                            writeExpr(a)
                            writer.append('>')
                        }
                    }
                    writer.append('"')
                    return
                }
            }
            if (op in CC_SETONCLICK..CC_SETONRESIZE || op in IF_SETONCLICK..IF_SETONRESIZE) {
                writeAddHook(expr)
                return
            }
            val branchInfix = BRANCH_INFIX_MAP[expr.id]
            val calcInfix = CALC_INFIX_MAP[expr.id]
            if (branchInfix != null) {
                writer.append('(')
                writeExpr(args[0])
                writer.append(' ')
                writer.append(branchInfix)
                writer.append(' ')
                writeExpr(args[1])
                writer.append(')')
            }  else if (calcInfix != null) {
                val wasCalc = inCalc
                if (!inCalc) {
                    writer.append("calc")
                    inCalc = true
                }
                writer.append('(')
                writeExpr(args[0])
                writer.append(' ')
                writer.append(calcInfix)
                writer.append(' ')
                writeExpr(args[1])
                writer.append(')')
                inCalc = wasCalc
            } else {
                var args2: List<Expression> = args
                if (expr.id in DOT_OPCODES) {
                    if ((args2.last() as Element.Constant).value == 1) {
                        writer.append('.')
                    }
                    args2 = args2.dropLast(1)
                }
                writer.append(names.getValue(op))
                if (args2.isNotEmpty()) {
                    writer.append('(')
                    writeExprList(args2)
                    writer.append(')')
                }
            }
        }

        private fun writeExprList(exprs: List<Expression>) {
            val es = exprs.iterator()
            if (es.hasNext()) {
                writeExpr(es.next())
            }
            while (es.hasNext()) {
                writer.append(", ")
                writeExpr(es.next())
            }
        }

        private fun writeAddHook(operation: Expression.Operation) {
            val args = operation.arguments.list<Expression>().toMutableList()
            val component = args.removeAt(args.lastIndex)

            if (operation.id < 2000 && (component as Element.Constant).value == 1) {
                writer.append('.')
            }
            writer.append(names[operation.id]).append('(')

            val invokeId = (args.removeAt(0) as Element.Constant).value as Int
            if (invokeId == -1) {
                writer.append(null)
            } else {
                val scriptName = scriptNameLoader.load(invokeId)
                writer.append('"')
                if (scriptName == null) {
                    writer.append("script").append(invokeId)
                } else {
                    writer.append(scriptName.strip("[clientscript,", ']'))
                }
                val triggerCount = (args.removeAt(args.lastIndex) as Element.Constant).value as Int
                val triggers = args.takeLast(triggerCount)
                repeat(triggerCount) { args.removeAt(args.lastIndex) }

                writer.append('(')
                writeExprList(args)
                writer.append(')')

                if (triggers.isNotEmpty()) {
                    writer.append('{')
                    writeExprList(triggers)
                    writer.append('}')
                }
                writer.append('"')
            }
            if (operation.id >= 2000) {
                writer.append(", ")
                writeExpr(component)
            }
            writer.append(')')
        }

        private fun writeInvoke(invoke: Expression.Operation) {
            val args = invoke.arguments.list<Expression>()
            writer.append('~')
            val invokeId = (args.first() as Element.Constant).value as Int
            val scriptName = scriptNameLoader.load(invokeId)
            if (scriptName == null) {
                writer.append("script").append(invokeId)
            } else {
                writer.append(scriptName.strip("[proc,", ']'))
            }
            if (args.size > 1) {
                writer.append('(')
                writeExprList(args.subList(1, args.size))
                writer.append(')')
            }
        }
    }

    private companion object {

        val CALC_INFIX_MAP = mapOf(
                ADD to "+",
                SUB to "-",
                MULTIPLY to "*",
                DIV to "/",
                MOD to "%",
                AND to "&",
                OR to "|"
        )

        val BRANCH_INFIX_MAP = mapOf(
                BRANCH_EQUALS to "=",
                BRANCH_GREATER_THAN to ">",
                BRANCH_GREATER_THAN_OR_EQUALS to ">=",
                BRANCH_LESS_THAN to "<",
                BRANCH_LESS_THAN_OR_EQUALS to "<=",
                BRANCH_NOT to "!",
                SS_OR to "|",
                SS_AND to "&"
        )

        val DOT_OPCODES = setOf(
                CC_CREATE,
                CC_DELETE,
                CC_FIND,
                IF_FIND,
                CC_SETPOSITION,
                CC_SETSIZE,
                CC_SETHIDE,
                CC_SETNOCLICKTHROUGH,
                _1006,
                CC_SETSCROLLPOS,
                CC_SETCOLOUR,
                CC_SETFILL,
                CC_SETTRANS,
                CC_SETLINEWID,
                CC_SETGRAPHIC,
                CC_SET2DANGLE,
                CC_SETTILING,
                CC_SETMODEL,
                CC_SETMODELANGLE,
                CC_SETMODELANIM,
                CC_SETMODELORTHOG,
                CC_SETTEXT,
                CC_SETTEXTFONT,
                CC_SETTEXTALIGN,
                CC_SETTEXTSHADOW,
                CC_SETOUTLINE,
                CC_SETGRAPHICSHADOW,
                CC_SETVFLIP,
                CC_SETHFLIP,
                CC_SETSCROLLSIZE,
                CC_RESUME_PAUSEBUTTON,
                _1122,
                CC_SETFILLCOLOUR,
                _1124,
                _1125,
                CC_SETLINEDIRECTION,
                _1127,
                CC_SETOBJECT,
                CC_SETNPCHEAD,
                CC_SETPLAYERHEAD_SELF,
                CC_SETOBJECT_NONUM,
                CC_SETOBJECT_ALWAYS_NUM,
                CC_SETOP,
                CC_SETDRAGGABLE,
                CC_SETDRAGGABLEBEHAVIOR,
                CC_SETDRAGDEADZONE,
                CC_SETDRAGDEADTIME,
                CC_SETOPBASE,
                CC_SETTARGETVERB,
                CC_CLEAROPS,
                CC_SETOPKEY,
                CC_SETOPTKEY,
                CC_SETOPKEYRATE,
                CC_SETOPTKEYRATE,
                CC_SETOPKEYIGNOREHELD,
                CC_SETOPTKEYIGNOREHELD,
                CC_GETX,
                CC_GETY,
                CC_GETWIDTH,
                CC_GETHEIGHT,
                CC_GETHIDE,
                CC_GETLAYER,
                CC_GETSCROLLX,
                CC_GETSCROLLY,
                CC_GETTEXT,
                CC_GETSCROLLWIDTH,
                CC_GETSCROLLHEIGHT,
                CC_GETMODELZOOM,
                CC_GETMODELANGLE_X,
                CC_GETMODELANGLE_Z,
                CC_GETMODELANGLE_Y,
                CC_GETTRANS,
                _1610,
                CC_GETCOLOUR,
                CC_GETFILLCOLOUR,
                _1613,
                _1614,
                CC_GETINVOBJECT,
                CC_GETINVCOUNT,
                CC_GETID,
                CC_GETTARGETMASK,
                CC_GETOP,
                CC_GETOPBASE,
                IF_CALLONRESIZE,
                CC_DRAGPICKUP,
                _3140
        )
    }
}