# 닉네임 금칙어 목록 출처 (BY-1135)

`en.txt`·`ja.txt`·`ko.txt`·`zh.txt`는 아래 목록을 바탕으로 만들었다.

- 원본: [List of Dirty, Naughty, Obscene, and Otherwise Bad Words](https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words)
  (Shutterstock이 시작한 목록), 커밋 `5faf2ba42d7b1c0977169ec3611df25a3c08eb13`의 `en`·`ja`·`ko`·`zh`
- 라이선스: [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/)
- 바꾼 점:
  - 닉네임에서 막으면 정상 이름까지 막히는 항목(일상어·검색어·정체성 용어·정치인 이름 등)을 뺐다. 예: `escort`, `santorum`, `女の子`, `ファンタジー`, `成人`, `笨蛋`, `유모`, `망가`.
  - 한국어 욕설 변형(`시발`, `존나`, `지랄` 등)과, 간체 항목의 번체 변형(대만·홍콩용)을 더했다.
  - NFKC 정규화·소문자로 바꾸고 중복을 지웠다.

`en-substring.txt`(단어 속에 들어 있어도 막는 영어 말), `reserved.txt`(운영자로 보이는 이름), `allow.txt`(금칙어를 품은 정상 단어)는
이 저장소에서 직접 정리했다.
