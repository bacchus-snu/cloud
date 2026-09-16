# 인스턴스 만들기

왼쪽 메뉴의 **Compute → Instances**에서 **Add Instance**를 누릅니다.

![인스턴스 목록과 Add Instance 버튼](images/instances.png)

순서대로 템플릿(운영체제), 컴퓨트 오퍼링(CPU·메모리·GPU), 데이터 디스크(선택), 이름을 고르면 됩니다. 네트워크는 하나뿐이라 자동으로 선택됩니다.

![인스턴스 만들기: 템플릿, 컴퓨트 오퍼링, 데이터 디스크](images/add-instance.png)

템플릿과 ISO는 운영진이 등록한 것만 쓸 수 있고 직접 올릴 수는 없습니다. 필요한 운영체제나 이미지가 있으면 <contact@bacchus.snucse.org>로 요청해 주세요. **컴퓨트 오퍼링은 정식 오픈 때 정해서 공지합니다.**

- 이름은 영문 소문자·숫자·하이픈만 쓰세요. SSH 접속 때 이 이름을 씁니다. 이름은 **서비스 전체에서 유일**해야 해서 다른 사람이 이미 쓴 이름이면 만들기가 실패합니다(`already exists in the network domain`). `<본인 유저명>-dev`처럼 자기 유저명을 앞에 붙이면 겹치지 않습니다.
- 만들어진 인스턴스는 목록에서 정지·시작·재시작·삭제할 수 있습니다. 컴퓨트 오퍼링 변경은 정지 상태에서만 됩니다.
- 삭제한 인스턴스와 볼륨은 복구되지 않으니 필요한 데이터는 미리 옮겨 두세요.
- 정지된 인스턴스도 볼륨과 쿼터를 그대로 차지합니다. 쓰지 않으면 삭제해 주세요.

## 템플릿

각 배포판의 공식 클라우드 이미지를 그대로 씁니다. 템플릿은 학기마다 새 이미지로 갱신하고, 인스턴스 안의 패키지는 배포판의 패키지 관리자(`apt`, `dnf`, `zypper`, `pacman`, `apk`)로 직접 업데이트하세요. SSH와 웹 콘솔은 아래 기본 사용자로 들어가며, 이 사용자는 `sudo`를 쓸 수 있습니다.

| 템플릿 | 기본 사용자 | 비고 |
| --- | --- | --- |
| Ubuntu 26.04 LTS | `ubuntu` | 기본 선택 |
| Ubuntu 24.04 LTS | `ubuntu` | |
| Debian 13 | `debian` | |
| Debian 12 | `debian` | |
| Fedora 44 | `fedora` | |
| Fedora 43 | `fedora` | |
| Rocky Linux 9 | `rocky` | |
| AlmaLinux 10 | `almalinux` | |
| AlmaLinux 9 | `almalinux` | |
| openSUSE Leap 16.0 | `opensuse` | |
| openSUSE Leap 15.6 | `opensuse` | |
| Arch Linux | `arch` | |
| Alpine Linux 3.22 | `alpine` | 첫 부팅이 2~3분 더 걸립니다(cloud-init이 다른 클라우드를 먼저 탐색) |
| Alpine Linux 3.21 | `alpine` | 위와 같음 |
