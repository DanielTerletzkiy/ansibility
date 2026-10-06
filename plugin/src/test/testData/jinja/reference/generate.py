"""Regenerates the jinja2 reference token streams (the lexer oracle) for AnsibleJinjaLexerOracleTest.

Run with a Python that has jinja2 3.1.x, e.g. ansible's venv:
    /opt/homebrew/Cellar/ansible/14.4.0/libexec/bin/python generate.py
It reads ../templates/*.j2 and snippets.txt and writes <template>.tokens and snippets.tokens next to this script.

Each output line is one significant token as jinja2's lexer (with the `do` and `loopcontrols` extensions, as Ansible
configures it) reports it: delimiters by kind, `comment` for a whole comment, `raw_begin`/`raw_end` for the raw tags,
and `name|string|integer|float|operator<TAB>value`. Outer text and whitespace are left out. EXPRESSION snippets are
lexed as `{{ <expr> }}` and the two delimiters are dropped.
"""
import os
import jinja2

HERE = os.path.dirname(os.path.abspath(__file__))
ENV = jinja2.Environment(extensions=['jinja2.ext.do', 'jinja2.ext.loopcontrols'])
KINDS = ('raw_begin', 'raw_end', 'variable_begin', 'variable_end', 'block_begin', 'block_end')


def esc(s):
    return s.replace('\\', '\\\\').replace('\n', '\\n').replace('\t', '\\t')


def tokens(source, mode):
    if mode == 'EXPRESSION':
        source = '{{ ' + source + ' }}'
    toks = list(ENV.lexer.tokeniter(source, None))
    if mode == 'EXPRESSION':
        toks = toks[1:-1]
    out = []
    for _, tok, val in toks:
        if tok in ('data', 'whitespace', 'comment', 'comment_end'):
            continue
        if tok == 'comment_begin':
            out.append('comment')
        elif tok in KINDS:
            out.append(tok)
        elif tok == 'string':
            out.append('string\t' + esc(val))
        else:
            out.append(tok + '\t' + val)
    return out


def main():
    templates = os.path.join(HERE, '..', 'templates')
    for name in sorted(os.listdir(templates)):
        if not name.endswith('.j2'):
            continue
        with open(os.path.join(templates, name), encoding='utf-8') as f:
            lines = tokens(f.read(), 'TEMPLATE')
        with open(os.path.join(HERE, name[:-3] + '.tokens'), 'w', encoding='utf-8') as f:
            f.write('\n'.join(lines) + '\n')
    with open(os.path.join(HERE, 'snippets.txt'), encoding='utf-8') as f:
        raw = f.read()
    out = []
    for index, block in enumerate(raw.split('--- ')[1:]):
        mode, _, body = block.partition('\n')
        body = body[:-1] if body.endswith('\n') else body
        out.append('### %d' % index)
        out.extend(tokens(body, mode.strip()))
    with open(os.path.join(HERE, 'snippets.tokens'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(out) + '\n')


if __name__ == '__main__':
    main()
