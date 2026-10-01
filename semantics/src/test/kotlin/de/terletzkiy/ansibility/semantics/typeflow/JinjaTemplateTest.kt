package de.terletzkiy.ansibility.semantics.typeflow

import de.terletzkiy.ansibility.semantics.value.PyValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigInteger

/** Template shapes and the expression grammar (jinja2 3.1 lexer and parser rules). */
class JinjaTemplateTest {
    private fun parse(text: String) = JinjaTemplate.parse(text, TestJinjaTokenizer)

    private fun expr(text: String): JinjaExpr = parse("{{ $text }}").singleExpression ?: error("no single expression in {{ $text }}")

    // ------------------------------------------------------------------------------------------------ shapes

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(
        delimiter = ';',
        textBlock = """
        '{{ x }}' ; SINGLE_EXPRESSION ; x
        '{{x}}' ; SINGLE_EXPRESSION ; x
        '{{   x   }}' ; SINGLE_EXPRESSION ; x
        '{{ x }}\n' ; SINGLE_EXPRESSION ; x
        '{{ x }}\n\n' ; MULTI_NODE ; 
        ' {{ x }}' ; MULTI_NODE ; 
        '{{ x }} ' ; MULTI_NODE ; 
        ' {{- x }}' ; SINGLE_EXPRESSION ; 
        '{{ x -}} ' ; SINGLE_EXPRESSION ; 
        '{# c #}{{ x }}' ; SINGLE_EXPRESSION ; 
        '{{ x }}{# c #}' ; SINGLE_EXPRESSION ; 
        '{{ x | int }}' ; SINGLE_EXPRESSION ; 
        '{{ true }}' ; SINGLE_EXPRESSION ; 
        '{{ x }}{{ y }}' ; MULTI_NODE ; 
        'a-{{ x }}' ; MULTI_NODE ; 
        '{% if b %}1{% endif %}' ; STATEMENTS ; 
        '{% raw %}{{ x }}{% endraw %}' ; TEXT_ONLY ; 
        '{# only a comment #}' ; TEXT_ONLY ; 
        '{{ x' ; MALFORMED ; 
        '{{ x | ( }}' ; MALFORMED ; 
        '{{ x $ y }}' ; MALFORMED ; """,
    )
    fun shapes(text: String, shape: TemplateShape, bare: String?) {
        val source = text.replace("\\n", "\n")
        val template = parse(source)
        assertEquals(shape, template.shape, source)
        assertEquals(bare, template.bareName, "bare name of $source")
    }

    @Test
    fun `whitespace control strips the text next to the tag`() {
        val template = parse("a  {{- x -}}  b")
        assertEquals(listOf("a", "b"), template.nodes.filterIsInstance<TemplateNode.Text>().map { it.text })
        assertEquals(TemplateShape.MULTI_NODE, template.shape)
    }

    @Test
    fun `output nodes keep the offsets of their delimiters`() {
        val template = parse("ab{{ x | int }}")
        val output = template.outputs.single()
        assertEquals(2, output.start)
        assertEquals(15, output.end)
        assertEquals(13, output.closeStart)
        val expression = requireNotNull(output.expression)
        assertEquals(5, expression.start)
        assertEquals(12, expression.end)
    }

    // ------------------------------------------------------------------------------------------------ expressions

    @Test
    fun `filters bind tighter than concatenation and inline if`() {
        val concat = assertInstanceOf(JinjaExpr.Concat::class.java, expr("a ~ b | int"))
        assertInstanceOf(JinjaExpr.Name::class.java, concat.parts[0])
        assertEquals("int", assertInstanceOf(JinjaExpr.Filter::class.java, concat.parts[1]).name)

        val conditional = assertInstanceOf(JinjaExpr.Conditional::class.java, expr("a if c else b | string"))
        assertEquals("a", (conditional.then as JinjaExpr.Name).name)
        assertEquals("c", (conditional.condition as JinjaExpr.Name).name)
        assertEquals("string", (conditional.otherwise as JinjaExpr.Filter).name)
    }

    @Test
    fun `filter chains, arguments and collection names`() {
        val outer = assertInstanceOf(JinjaExpr.Filter::class.java, expr("x | default(3, true) | int"))
        assertEquals("int", outer.name)
        val inner = assertInstanceOf(JinjaExpr.Filter::class.java, outer.target)
        assertEquals("default", inner.name)
        assertEquals(listOf(PyValue.Int(3), PyValue.Bool(true)), inner.args.map { (it as JinjaExpr.Const).value })

        val fqcn = assertInstanceOf(JinjaExpr.Filter::class.java, expr("d | ansible.builtin.to_json(indent=2)"))
        assertEquals("ansible.builtin.to_json", fqcn.name)
        assertEquals("to_json", fqcn.shortName)
        assertEquals(setOf("indent"), fqcn.kwargs.keys)

        val join = assertInstanceOf(JinjaExpr.Filter::class.java, expr("items | join(',')"))
        assertEquals(PyValue.Str(","), (join.args.single() as JinjaExpr.Const).value)
    }

    @Test
    fun `attribute, item and slice access`() {
        val item = assertInstanceOf(JinjaExpr.Subscript::class.java, expr("x.y[0]['z']"))
        assertEquals(PyValue.Str("z"), (item.key as JinjaExpr.Const).value)
        val index = assertInstanceOf(JinjaExpr.Subscript::class.java, item.target)
        assertEquals(PyValue.Int(0), (index.key as JinjaExpr.Const).value)
        assertEquals("y", assertInstanceOf(JinjaExpr.Attribute::class.java, index.target).attribute)

        val dotted = assertInstanceOf(JinjaExpr.Subscript::class.java, expr("x.0"))
        assertEquals(PyValue.Int(0), (dotted.key as JinjaExpr.Const).value)
        assertNull(assertInstanceOf(JinjaExpr.Subscript::class.java, expr("x[1:]")).key)
        assertNull(assertInstanceOf(JinjaExpr.Subscript::class.java, expr("x[::2]")).key)
    }

    @Test
    fun `tests, comparisons and boolean operators`() {
        val test = assertInstanceOf(JinjaExpr.Test::class.java, expr("x is not defined"))
        assertEquals("defined", test.name)
        assertEquals(true, test.negated)
        assertEquals("divisibleby", assertInstanceOf(JinjaExpr.Test::class.java, expr("x is divisibleby 3")).name)
        assertEquals("not", assertInstanceOf(JinjaExpr.Unary::class.java, expr("not a == b")).operator)
        assertEquals("not in", assertInstanceOf(JinjaExpr.Binary::class.java, expr("a not in b")).operator)
        assertEquals("or", assertInstanceOf(JinjaExpr.Binary::class.java, expr("a and b or c")).operator)
        val negated = assertInstanceOf(JinjaExpr.Filter::class.java, expr("-x | abs"))
        assertInstanceOf(JinjaExpr.Unary::class.java, negated.target)
    }

    @Test
    fun `literals`() {
        assertEquals(PyValue.Str("ab"), (expr("'a' \"b\"") as JinjaExpr.Const).value)
        assertEquals(PyValue.Str("a\nA\\q"), (expr("'a\\n\\x41\\q'") as JinjaExpr.Const).value)
        assertEquals(PyValue.Int(BigInteger.valueOf(31)), (expr("0x1F") as JinjaExpr.Const).value)
        assertEquals(PyValue.Int(1000), (expr("1_000") as JinjaExpr.Const).value)
        assertEquals(PyValue.Float(2.5), (expr("2.5") as JinjaExpr.Const).value)
        assertEquals(PyValue.None, (expr("none") as JinjaExpr.Const).value)
        assertEquals(PyValue.Bool(false), (expr("False") as JinjaExpr.Const).value)
        assertEquals(2, assertInstanceOf(JinjaExpr.ListLiteral::class.java, expr("[1, 'a',]")).items.size)
        assertEquals(2, assertInstanceOf(JinjaExpr.DictLiteral::class.java, expr("{'a': 1, 'b': [1, 2]}")).entries.size)
        assertEquals(2, assertInstanceOf(JinjaExpr.TupleLiteral::class.java, expr("(1, 2)")).items.size)
        assertEquals(2, assertInstanceOf(JinjaExpr.TupleLiteral::class.java, expr("1, 2")).items.size)
        assertInstanceOf(JinjaExpr.Name::class.java, expr("(x)"))
        assertInstanceOf(JinjaExpr.Call::class.java, expr("lookup('env', 'HOME')"))
    }
}
