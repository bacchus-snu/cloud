# 인스턴스에 접속하기

인스턴스는 외부에서 직접 닿을 수 없는 내부 주소를 받습니다. SSH는 접속 게이트웨이(cloud.snucse.org의 2222번 포트)를 거치고, 화면은 웹 UI의 콘솔로 봅니다.

## SSH

```console
$ ssh -p 2222 <SNUCSE ID 유저명>:<인스턴스 이름>@cloud.snucse.org
```

처음 접속하면 터미널에 로그인 링크가 나옵니다. 그 링크를 브라우저로 열어 SNUCSE ID로 로그인하면 접속이 이어집니다. **SSH 키를 만들어 등록할 필요가 없습니다.** 접속 게이트웨이가 SNUCSE ID로 본인을 확인하고 인스턴스에 대신 들어가므로, 어느 컴퓨터에서든 브라우저 로그인만으로 접속할 수 있습니다. 인스턴스 안에서는 템플릿의 기본 사용자로 들어가며, 접속 게이트웨이에서 오는 SSH는 인스턴스에 자동으로 허용되어 있습니다.

`~/.ssh/config`에 적어 두면 `scp`, `sftp`, VS Code Remote-SSH 등도 그대로 쓸 수 있습니다.

```
Host my-vm
    HostName cloud.snucse.org
    Port 2222
    User <SNUCSE ID 유저명>:my-vm
```

```console
$ ssh my-vm
$ scp data.tar.gz my-vm:
```

본인 계정의 인스턴스와, 구성원으로 속한 [프로젝트](project.md)의 인스턴스에 접속할 수 있습니다.

## 웹 콘솔

**Compute → Instances**에서 인스턴스를 고르고 오른쪽 위의 **View console**(모니터 아이콘)을 누르면 새 창에 화면이 뜹니다. 부팅이 안 되거나 SSH가 막혔을 때 씁니다. 콘솔에서 로그인하려면 비밀번호가 필요하므로, 먼저 SSH로 들어가 `sudo passwd <사용자>`로 비밀번호를 정해 두세요.
