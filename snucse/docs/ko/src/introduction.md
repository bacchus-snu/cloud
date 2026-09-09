# SNUCSE Cloud

<div class="warning">

SNUCSE Cloud는 **준비 중**입니다. 신규 자원 편입과 리소스 쿼터 조정 작업을 진행하고 있어, 지금은 로그인해서 둘러볼 수만 있고 인스턴스를 만들 수는 없습니다. 이 안내도 시험 운영 기준으로 쓴 것이라, 제공할 템플릿과 인스턴스 종류, 쿼터 신청 방법은 정식 오픈 때 정해서 [SNUCSE ID](https://id.snucse.org)와 이 문서에서 공지합니다.

</div>

안녕하세요, 서울대학교 컴퓨터공학부 서버 관리자 모임 바쿠스입니다.

SNUCSE Cloud는 컴퓨터공학부 주전공 회원과 허가된 외부 회원에게 가상 머신(인스턴스)을 제공하는 서비스입니다. 필요하면 GPU가 달린 인스턴스도 만들 수 있습니다. [Apache CloudStack](https://cloudstack.apache.org)으로 운영합니다.

| 무엇을 | 어디서 |
| --- | --- |
| 인스턴스 만들기·관리 | 웹 UI <https://cloud.snucse.org> |
| 인스턴스에 SSH로 접속 | `ssh -p 2222 <유저명>:<인스턴스 이름>@cloud.snucse.org` |
| 인스턴스 화면 보기 | 웹 UI의 콘솔 |

모든 것이 [SNUCSE ID](https://id.snucse.org)와 연동되어 있습니다. 계정을 따로 만들지 않고 SNUCSE ID로 로그인하면 되고, 인스턴스에 SSH로 접속할 때도 다른 클라우드처럼 SSH 키를 만들어 등록할 필요 없이 브라우저에서 SNUCSE ID로 로그인하면 됩니다. 자세한 내용은 [이용 대상과 로그인](account.md)과 [인스턴스에 접속하기](access.md)를 보세요.

문의는 <contact@bacchus.snucse.org>로 보내 주세요.
