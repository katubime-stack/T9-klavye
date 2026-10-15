import re
import urllib.request

ADRESLER = [
    "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/tr/tr_full.txt",
    "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/tr/tr_50k.txt",
    "https://huggingface.co/datasets/StephanAkkerman/frequency-words-2018/resolve/main/tr/tr_full.txt",
]
LIMIT = 100000
HARFLER = set("abcçdefgğhıijklmnoöprsştuüvyz")
TEMEL = "app/src/main/assets/kelimeler.txt"
CIKTI = "app/src/main/assets/sozluk.txt"

kelimeler = []
gorulen = set()


def ekle(k):
    if k and k not in gorulen:
        gorulen.add(k)
        kelimeler.append(k)


try:
    with open(TEMEL, encoding="utf-8") as f:
        for k in f.read().split():
            ekle(k.lower())
except Exception as e:
    print("temel sozluk okunamadi:", e)

veri = None
for adres in ADRESLER:
    try:
        veri = urllib.request.urlopen(adres, timeout=120).read().decode("utf-8")
        print("indirildi:", adres)
        break
    except Exception as e:
        print("indirilemedi:", adres, e)

if veri:
    for satir in veri.splitlines():
        parca = satir.split()
        if not parca:
            continue
        k = parca[0]
        if any(c not in HARFLER for c in k):
            continue
        if len(k) < 2 and k != "o":
            continue
        if re.search(r"(.)\1\1", k):
            continue
        ekle(k)
        if len(kelimeler) >= LIMIT:
            break

with open(CIKTI, "w", encoding="utf-8") as f:
    f.write("\n".join(kelimeler))
print(len(kelimeler), "kelime yazildi")
