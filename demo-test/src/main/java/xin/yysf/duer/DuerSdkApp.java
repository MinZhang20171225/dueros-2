package xin.yysf.duer;

import com.baidu.duer.dcs.devicemodule.screen.ScreenDeviceModule;
import com.baidu.duer.dcs.devicemodule.screen.message.RenderVoiceInputTextPayload;
import com.baidu.duer.dcs.devicemodule.voiceinput.VoiceInputDeviceModule;
import com.baidu.duer.dcs.framework.DcsFramework;
import com.baidu.duer.dcs.framework.DeviceModuleFactory;
import com.baidu.duer.dcs.http.HttpConfig;
import com.baidu.duer.dcs.http.intercepter.LoggingInterceptor;
import com.baidu.duer.dcs.systeminterface.IWebView;
import com.baidu.duer.dcs.util.LogUtil;
import com.baidu.duer.dcs.wakeup.WakeUp;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import xin.yysf.duer.po.DuerAuthToken;

import javax.annotation.PostConstruct;
import java.io.*;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
@Controller
public class DuerSdkApp implements VoiceInputDeviceModule.IVoiceInputListener{
    private static Logger log= LoggerFactory.getLogger(DuerSdkApp.class);
    public static final long DEFAULT_MILLISECONDS = 60 * 1000L;
    public static String clientId;
    public static String clientSecret;
    static {

        File file=new File("duer.properties");
        InputStream in=null;
        if(file.exists()){
            try {
                in=new FileInputStream(file);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            log.info("读取配置文件duer.properties：{}",file.getAbsolutePath());
        }else{
            log.warn("没有找到配置文件duer.properties：{}",file.getAbsolutePath());
        }
        if(in==null){
            log.info("从classpath读取");
            in=DuerSdkApp.class.getResourceAsStream("/duer.properties");
        }
        if(in!=null){
            Properties properties=new Properties();
            try {
                properties.load(in);
                clientId=properties.getProperty("clientId");
                clientSecret=properties.getProperty("clientSecret");
            } catch (IOException e) {
                e.printStackTrace();
            }finally {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }else{
            System.err.println("请在classpath中配置duer.properties");
            System.exit(1);
        }

    }

    public String url = "https://openapi.baidu.com/oauth/2.0/authorize?response_type=code&redirect_uri=http%3A%2F%2F127.0.0.1%3A8080%2Fauth&client_id=" + clientId;


    String tokenUrl = "https://openapi.baidu.com/oauth/2.0/token?grant_type=authorization_code&redirect_uri=http%3A%2F%2F127.0.0.1%3A8080%2Fauth&client_id="
            + clientId + "&client_secret=" + clientSecret + "&code=";

    String duerosHost = "https://dueros-h2.baidu.com";

    OkHttpClient client = null;
    public static DuerAuthToken token;

    public WindowsPlatformFactory factory;

    @Autowired
    public ObjectMapper objectMapper;
    public DcsFramework dcsFramework;
    public static DuerSdkApp sdkApp;
    private IWebView webView;
    private WakeUp wakeUp;
    private boolean isStopListenReceiving;

    public DuerSdkApp(){
        sdkApp=this;
        factory=new WindowsPlatformFactory();

    }
    @GetMapping("auth")
    @ResponseBody
    public String auth(@RequestParam String code) {
        Response rs = null;
        try {
            rs = client.newCall(new Request.Builder().url(tokenUrl + code).get().build()).execute();
            String txt = rs.body().string();
            DuerAuthToken token = objectMapper.readValue(txt, DuerAuthToken.class);
            if (token.expires_in != null) {
                DuerSdkApp.token = token;
                token.time=System.currentTimeMillis();
                initDuerOs();

                File file=new File("duer.dat");
                objectMapper.writeValue(file,token);
                webView.loadContent("<h1>HelloDuerOS</h1>");
            }
            System.out.println(token);
            return txt;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (rs != null) {
                rs.close();
            }
        }
        return "授权失败";
    }
    @PostConstruct
    public void initDuerOs() {
        if(client==null){
            client = new OkHttpClient.Builder()
                    .retryOnConnectionFailure(false)
                    .readTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
                    .writeTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
                    .connectTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
                    .addInterceptor(new LoggingInterceptor()).build();
        }
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }
        if(token==null){
            log.warn("没有授权,请访问授权：{}",url);

            return;
        }
        HttpConfig.setAccessToken(token.access_token);

        dcsFramework = new DcsFramework(factory);

        DeviceModuleFactory fac = dcsFramework.getDeviceModuleFactory();
        fac.createSystemDeviceModule();
        fac.createAudioPlayerDeviceModule();
        fac.createVoiceInputDeviceModule();

        fac.getVoiceInputDeviceModule().addVoiceInputListener(this);

        fac.createVoiceOutputDeviceModule();
        if(factory.getWebView()!=null){
            fac.createScreenDeviceModule();
            fac.getScreenDeviceModule().addRenderVoiceInputTextListener(payload -> webView.setText(payload.text));
        }

    // init唤醒
        wakeUp = new WakeUp(factory.getWakeUp(),
                factory.getAudioRecord());
        wakeUp.addWakeUpListener(()->{
            System.out.println("唤醒成功");
            if (isStopListenReceiving) {
                factory.getVoiceInput().stopRecord();
                isStopListenReceiving = false;
                return;
            }
            isStopListenReceiving = true;
            factory.getVoiceInput().startRecord();

        });
        // 开始录音，监听是否说了唤醒词
        wakeUp.startWakeUp();

    }
    public void shutdown(){
        if(factory!=null)
            factory.shutdown();
        if(client!=null){
            client.dispatcher().cancelAll();
            client.dispatcher().executorService().shutdownNow();
        }
        if(dcsFramework!=null){
            dcsFramework.release();
        }
        if(wakeUp!=null){
            wakeUp.stopWakeUp();
            factory.getAudioRecord().stopRecord();
        }
    }


    public static DuerSdkApp createDuerOs() {

        File file=new File("duer.dat");
        if(file.exists()){
            FileInputStream in=null;
            try {
                in=new FileInputStream(file);
                token=new ObjectMapper().readValue(new FileInputStream(file),DuerAuthToken.class);
            } catch (IOException e) {
                e.printStackTrace();
            }finally {
                if(in!=null){
                    try {
                        in.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            if(token!=null){
                if(System.currentTimeMillis()-token.time> 3600*1000*28*24L){
                    log.warn("token过期");
                    token=null;
                }
            }
        }
        if(token==null){
            CountDownLatch lock=new CountDownLatch(1);
            new Thread(()->{
                SpringApplication.run(DuerSdkApp.class);
                lock.countDown();
            }).start();
            try {
                lock.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }else{
            new DuerSdkApp();
            sdkApp.initDuerOs();
        }
        return sdkApp;


//        try {
//            Response response = sdkApp.client.newCall(new Request.Builder()
//                    .url("https://http2.golang.org/reqinfo")
//                    .build()).execute();
//
//            System.out.println(response.body().string());
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
    }

    public void setWebView(IWebView webView) {
        this.webView=webView;
        factory.setWebView(webView);
        if(dcsFramework!=null){
            dcsFramework.getDeviceModuleFactory().createScreenDeviceModule();
        }
    }

    @Override
    public void onStartRecord() {
        wakeUp.stopWakeUp();
        isStopListenReceiving = false;
    }

    @Override
    public void onFinishRecord() {
        wakeUp.startWakeUp();
        isStopListenReceiving = false;
    }

    @Override
    public void onSucceed(int statusCode) {
        log.info("onSucceed-statusCode:{}",statusCode);
    }

    @Override
    public void onFailed(String errorMessage) {
        log.error("onFailed-errorMessage::{}",errorMessage);
    }
}
