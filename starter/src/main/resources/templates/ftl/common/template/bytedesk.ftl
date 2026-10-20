<!--SelfDesk客服代码 开始 -->
<script src="http://127.0.0.1:9003/chat/assets/js/float/index.min.js"></script>
<script>
 var showKefu = ${knowledgebase.showChat?c}
 if (showKefu) {
    window.ChatFloat({
        chatUrl: 'http://127.0.0.1:9003/chat/?org=df_org_uid&t=1&sid=df_wg_uid&&',
    });
 }
 </script>
<!--./SelfDesk客服代码 结束 -->
