# How values render: template text vs task-arg native type, YAML 1.1 typed values from a vars file.
VARS = {"d": {"b": 2, "a": 1}, "strs": ["b", "a"], "none_v": None, "b_true": True, "f": 2.5,
        "q": {"k": "it's \"q\""}, "nested": {"l": [1, {"x": None}], "t": True},
        "unicode_s": "Grüße"}
VARS_YAML = """
y_yes: yes
y_on: on
y_True: True
y_octal: 0644
y_octal_q: "0644"
y_octal_0o: 0o644
y_sexa: 1:30
y_float_ver: 3.10
y_exp: 1e3
y_exp_dot: 1.0e3
y_date: 2024-01-31
y_datetime: 2024-01-31 10:20:30
y_tilde: ~
y_empty:
y_inf: .inf
y_nan: .nan
y_hex: 0x1F
y_underscore: 1_000
y_unsafe: !unsafe "{{ not_templated }}"
y_bin: 0b101
y_plus: +12
y_str_null: "null"
y_multi_literal: |
  line1
  line2
y_multi_folded: >
  a
  b
y_list_flow: [a, 1, yes]
"""
CASES = [
    ("r_dict", "d"), ("r_list", "strs"), ("r_none", "none_v"), ("r_true", "b_true"), ("r_float", "f"),
    ("r_quote_dict", "q"), ("r_nested", "nested"), ("r_unicode_list", "[unicode_s]"),
    ("r_tuple", "(1, 'a')"), ("r_set", "[1, 1, 2] | unique"),
    ("r_y_yes", "y_yes"), ("r_y_on", "y_on"), ("r_y_True", "y_True"), ("r_y_octal", "y_octal"),
    ("r_y_octal_q", "y_octal_q"), ("r_y_octal_0o", "y_octal_0o"), ("r_y_sexa", "y_sexa"),
    ("r_y_float_ver", "y_float_ver"), ("r_y_exp", "y_exp"), ("r_y_exp_dot", "y_exp_dot"),
    ("r_y_date", "y_date"), ("r_y_datetime", "y_datetime"), ("r_y_tilde", "y_tilde"), ("r_y_empty", "y_empty"),
    ("r_y_inf", "y_inf"), ("r_y_nan", "y_nan"), ("r_y_hex", "y_hex"), ("r_y_underscore", "y_underscore"),
    ("r_y_unsafe", "y_unsafe"), ("r_y_bin", "y_bin"), ("r_y_plus", "y_plus"), ("r_y_str_null", "y_str_null"),
    ("r_y_multi_literal", "y_multi_literal"), ("r_y_multi_folded", "y_multi_folded"), ("r_y_list_flow", "y_list_flow"),
    ("r_y_date_attr", "y_date.year"), ("r_y_date_str", "y_date | string"), ("r_y_date_tojson", "y_date | to_json"),
    ("r_y_unsafe_upper", "y_unsafe | upper"),
    ("r_dict_in_str", "'x' ~ d"), ("r_list_join", "strs | join(', ')"),
]
