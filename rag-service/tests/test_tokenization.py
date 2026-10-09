import pytest

from app.tokenization import tokenize


@pytest.mark.parametrize(
    ("query", "expected_tokens"),
    [
        ("控制三高", ["三高", "高血", "血压", "糖尿", "尿病", "血脂"]),
        ("心梗", ["心梗", "心脏病", "脏病发", "病发作"]),
        ("脑卒中", ["脑卒中", "中风"]),
        ("血压偏高", ["血压", "压偏", "偏高", "高血", "血压"]),
    ],
)
def test_domain_aliases_expand_to_existing_corpus_terms(query, expected_tokens):
    tokens = tokenize(query)

    for expected in expected_tokens:
        assert expected in tokens


def test_original_corpus_vocabulary_is_preserved():
    query_features = {
        "高血压": ["高血压", "高血", "血压"],
        "糖尿病": ["糖尿病", "糖尿", "尿病"],
        "高血脂": ["高血脂", "高血", "血脂"],
        "心脏病发作": ["心脏病", "脏病发", "病发作"],
        "中风": ["中风"],
    }

    for query, features in query_features.items():
        tokens = tokenize(query)
        assert set(features).issubset(tokens)


def test_unrelated_text_does_not_receive_domain_expansions():
    tokens = tokenize("量子纠错编码")

    assert not {"高血压", "糖尿病", "高血脂", "心脏病发作", "中风"}.intersection(tokens)
