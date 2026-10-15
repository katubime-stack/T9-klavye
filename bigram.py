import os
import re
from collections import Counter, defaultdict
from datasets import load_dataset

CIKTI = "app/src/main/assets/bigram.tsv"
MAKS_MADDE = 100000
ANAHTAR_LIMIT = 30000
HER_KELIME_ADAY = 5
MIN_SAYI = 5
HARFLER = set("abcçdefgğhıijklmnoöprsştuüvwxyz")


def tr_lower(s):
    return s.replace("İ", "i").replace("I", "ı").lower()


ds = load_dataset("wikimedia/wikipedia", "20231101.tr",
                  split="train", streaming=True)

sayac = Counter()
for n, madde in enumerate(ds):
    if n >= MAKS_MADDE:
        break
    metin = re.sub(r"['’]\w*", "", madde["text"])
    for cumle in re.split(r"[.!?;:\n]+", metin):
        kel = [tr_lower(k) for k in re.findall(r"[^\W\d_]+", cumle)]
        kel = [k if len(k) > 1 and all(c in HARFLER for c in k) else None
               for k in kel]
        for a, b in zip(kel, kel[1:]):
            if a and b:
                sayac[(a, b)] += 1
    if n % 10000 == 0:
        print(n, "madde islendi")

gruplar = defaultdict(list)
toplam = Counter()
for (a, b), c in sayac.items():
    if c >= MIN_SAYI:
        gruplar[a].append((c, b))
        toplam[a] += c

os.makedirs(os.path.dirname(CIKTI), exist_ok=True)
with open(CIKTI, "w", encoding="utf-8") as f:
    for a, _ in toplam.most_common(ANAHTAR_LIMIT):
        en_iyi = sorted(gruplar[a], reverse=True)[:HER_KELIME_ADAY]
        f.write(a + "\t" + ",".join(b for _, b in en_iyi) + "\n")
print("bitti")
