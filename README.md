# Overview
This is Live Streaming Plug-in.
RTSP server starts on RICOH THETA.

# Terms of Service


# 開発環境
Android Studio

# インストール
Android Studioを使用し、本アプリケーションをビルドしインストールしてください。

# 準備

1. Vyserなどのデスクトップビューツールを使用して、本アプリケーションのPermissionを許可してください。
1. [SmartPhone app > Basic app](https://support.theta360.com/en/download/)をインストールしてください。

# 使い方

1. THETAカメラの電源ボタンを押し、THETAを起動してください。
1. SmartPhon appより、Thetaを[Wireless-LAN　client mode]((https://support.theta360.com/uk/manual/v/content/prepare/prepare_08.html))に設定してください。（wireless-LAN LEDが緑色に点灯）
1. SmartPhon appの設定 > カメラ設定 > プラグイン　より本プラグインを選択してください。
1. THETAカメラのモードボタンを長押しし、プラグインを起動してください。
1. 正常に起動すると、VIDEO LEDが点灯状態、白色のLEDが点灯状態になります。このとき、RTSPサーバーが起動されています。
1. vlcなどお好みのRTSPクライアントツールよりTHETA RTSP Serverにアクセスしてください。
```
rtsp://[TEATA IP ADDRESS]/live?resolution=1920x960
```

THETA IP ADDRESS: THETAカメラのIPアドレス

THETAカメラのIPアドレスはSmartPhon appより確認できます。

resolution パラメータにて解像度の指定ができます。
640x320,1024x512,1920x960,3840x1920のいづれかを設定できます。
ただし、3840x1920は十分なストリーミングスピードが得られません。

# 注意事項
本RTSPサーバーはユニキャストのみ対応となっています。

resolutionパラメータより、解像度を変更する場合は TEARDOWN リクエスト送信後、新しいrtspセッションを開始するようにしてください。（vlcを使用する場合はStop Play Backボタン押下後、新しいセッションを開始してください。）

