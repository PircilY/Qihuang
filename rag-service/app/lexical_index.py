"""Incremental shared-token BM25 with corpus-wide statistics."""
from collections import Counter, defaultdict
import math
from llama_index.core.schema import NodeWithScore
from app.tokenization import tokenize
from app.terminology import POLICY, is_generic


def searchable(node):
    metadata = node.metadata
    return " ".join([str(metadata.get(key) or "").replace("|", " ") for key in
                     ("document_title", "disease", "syndromes", "formulas", "herbs", "keywords")] + [node.text])


class LexicalIndex:
    def __init__(self):
        self.entries = {}
        self.postings = defaultdict(set)
        self.average_length = 1.0
        self.idf = {}
        self.features = {}
        self.topic_frequency = Counter()
        self.document_count = 0

    def update(self, nodes):
        incoming = {node.node_id: node for node in nodes}
        for node_id in list(self.entries):
            previous, terms, _ = self.entries[node_id]
            node = incoming.get(node_id)
            if node is None or searchable(previous) != searchable(node):
                for term in terms:
                    self.postings[term].discard(node_id)
                del self.entries[node_id]
        for node_id, node in incoming.items():
            if node_id not in self.entries:
                terms = Counter(tokenize(searchable(node)))
                self.entries[node_id] = (node, terms, sum(terms.values()))
                for term in terms:
                    self.postings[term].add(node_id)
        self.topic_frequency = Counter()
        self.document_count = len(incoming)
        for node_id, node in incoming.items():
            if node_id not in self.features or self.features[node_id][0] != searchable(node):
                topics = [str(node.metadata.get("disease") or "")]
                for key in ("keywords", "herbs", "formulas", "syndromes"):
                    topics.extend(str(node.metadata.get(key) or "").split("|"))
                topic_values = ["".join(topic.lower().split()) for topic in topics
                                if len(topic) >= 2 and topic.strip()]
                self.features[node_id] = (searchable(node), set(tokenize(node.text)),
                    set(tokenize(str(node.metadata.get("disease") or ""))),
                    topic_values)
        self.features = {key: value for key, value in self.features.items() if key in incoming}
        for value in self.features.values():
            self.topic_frequency.update(set(value[3]))
        self.average_length = max(1, sum(entry[2] for entry in self.entries.values()) / max(1, len(self.entries)))
        count = len(self.entries)
        self.idf = {term: math.log(1 + (count - len(ids) + .5) / (len(ids) + .5))
                    for term, ids in self.postings.items() if ids}

    def related(self, question, node, query=None, strict_topics=False):
        features = self.features.get(node.node.node_id)
        if features is None:
            return False
        _, content, disease, topics = features
        generic = {"证据", "研究", "安全", "疗效", "检查", "症状", "未归类"}
        query = set(query or tokenize(question))
        query = {term for term in query if term not in generic and not is_generic(term)}
        normalized = "".join(question.lower().split())
        maximum_frequency = float(POLICY["maximumRelatedFrequency"])
        anchored_topics = [topic for topic in topics
                           if not is_generic(topic)
                           if not strict_topics or self.document_count == 0
                           or self.topic_frequency.get(topic, 0) / self.document_count <= maximum_frequency]
        return any(topic in normalized for topic in anchored_topics) or bool(query & disease) or len(query & content) >= 3

    def retrieve(self, question, limit, accepts, query_features=None):
        query = query_features or Counter(tokenize(question))
        candidates = set().union(*(self.postings.get(term, set()) for term in query)) if query else set()
        results = []
        for node_id in candidates:
            node, terms, length = self.entries[node_id]
            wrapped = NodeWithScore(node=node, score=0)
            if not accepts(wrapped):
                continue
            score = 0.0
            for term, weight in query.items():
                tf = terms.get(term, 0)
                if tf:
                    score += weight * self.idf.get(term, 0) * tf * 2.4 / (tf + 1.4 * (.28 + .72 * length / self.average_length))
            if score > 0:
                results.append(NodeWithScore(node=node, score=score))
        return sorted(results, key=lambda item: (-item.score, item.node.node_id))[:limit]
