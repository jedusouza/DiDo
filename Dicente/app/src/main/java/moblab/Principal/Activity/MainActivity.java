package moblab.Principal.Activity;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.StrictMode;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import com.github.kittinunf.fuel.Fuel;
import com.github.kittinunf.fuel.core.FuelError;
import com.github.kittinunf.fuel.core.Handler;
import com.github.kittinunf.fuel.core.Request;
import com.github.kittinunf.fuel.core.Response;
import com.github.kittinunf.result.Result;
import com.moblab.tethering.GerenciaRedeD2D;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.Triple;
import moblab.Principal.Adapters.AdapterListView;
import moblab.Principal.Conexoes.ConexaoCliente;
import moblab.Principal.Entidades.Arquivo;
import moblab.Principal.Entidades.ItemListView;
import moblab.Principal.R;
import moblab.Principal.Conexoes.TaskReceberMSGServer;

public class MainActivity extends AppCompatActivity {

    public static ListView listaView; // Este e a lista de itens do layout.
    public static List<ItemListView> listaItensView; // Essa e a lista de itens que contem as mensagens.
    public static List<Arquivo> arquivos;
    public static AdapterListView adaptador; // Essa e o adaptador da lista do layout.
    public String nome = "SemNome"; // Essa variavel contem o nome do usuario.
    public String IP = "http://10.42.0.1:8080";
    public String turmaId;
    public static boolean INTERNET = true;
    public static List<String> msgsMC = new ArrayList<>();
    public static List<String> clientesAtuais = new ArrayList<>();
    public ExecutorService pool = Executors.newFixedThreadPool(1000);
    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 1;
    private static final int PERMISSION_REQUEST_FINE_LOCATION = 2;

    private GerenciaRedeD2D gerenciaRedeD2D = null;
    public TaskReceberMSGServer taskReceberMSGServer = null;
    public List<ContataCliente> ObterclientesAtuais = new ArrayList<>();



    private DownloadManager mgr=null;
    private long lastDownload=-1L;
    private String downloadDirectory = "DiDo/arquivos/";

    // Metodo inicial da aplicacao. Tudo comeca aqui. Configuracao do layout inicial e
    // chamada do metodo para configurar o nome, e configuracao da lista de mensagens.
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        givePermission();


        checkSystemWritePermission();
        Intent intent = getIntent();
        turmaId =intent.getStringExtra("turma");
        obterNome();

        if (android.os.Build.VERSION.SDK_INT > 9) {
            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);
        }


        this.listaView = (ListView) findViewById(R.id.lista_itens);

        this.listaItensView = new ArrayList<ItemListView>();
        this.arquivos = new ArrayList<Arquivo>();

        this.adaptador = new AdapterListView(this, this.listaItensView);

        this.listaView.setAdapter(this.adaptador);

        ((EditText) findViewById(R.id.editText)).setHint("Mensagem");

        checarPermissoes();

        new ReceberMSG().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        new ObterMensagensTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

        gerenciaRedeD2D = new GerenciaRedeD2D(MainActivity.this);
        gerenciaRedeD2D.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

        clientesAtuais.add(getMyIP());

        iniciaObterClientes();

        //obtém urls
        new GetFilesURLS().execute(IP+"/sistema/obterArquivos?turma="+turmaId);

        listaView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                ItemListView item = (ItemListView) adapterView.getItemAtPosition(i);

                    if (item.isFile()){
                        if (statusArquivo(item.getNome())){
                            openFile(item.getNome());

                        }else{
                            String urlDown = item.getUrl();
                            String filename = item.getNome();
                            startDownload(urlDown,filename);
                        }
                    }

            }
        });


        mgr=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        registerReceiver(onComplete,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        registerReceiver(onNotificationClick,
                new IntentFilter(DownloadManager.ACTION_NOTIFICATION_CLICKED));

    }
    public boolean statusArquivo(String name) {
        File file = new File(Environment.getExternalStorageDirectory().getPath()+"/mounted/"+downloadDirectory+turmaId);

        File[] files = file.listFiles();
        Log.d("locais",Environment.getExternalStorageDirectory().getPath());
        for (File f : files){
            int tamanho = (f.getPath().split("/")).length;
            if ((f.getPath().split("/"))[tamanho-1].equals(name)){
                return true;
            }
        }
    return false;
    }
    //Download


    @Override
    public void onDestroy(){
        super.onDestroy();

        unregisterReceiver(onComplete);
        unregisterReceiver(onNotificationClick);
    }

    public void startDownload(String url,String filename){
        createDirectory();

        Uri uri=Uri.parse(url);

        Environment
                .getExternalStoragePublicDirectory(Environment.getExternalStorageDirectory()
                        + "/" + downloadDirectory)
                .mkdirs();

        lastDownload=
                mgr.enqueue(new DownloadManager.Request(uri)
                        .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI |
                                DownloadManager.Request.NETWORK_MOBILE)
                        .setAllowedOverRoaming(false)
                        .setTitle("Download")
                        .setDescription("Realizando Download")
                        .setDestinationInExternalPublicDir(Environment.getExternalStorageState()
                                + "/" + downloadDirectory+turmaId, filename));

    }


    BroadcastReceiver onComplete = new BroadcastReceiver(){
        public void onReceive(Context ctxt, Intent intent){
            //openFile();
        }
    };

    BroadcastReceiver onNotificationClick = new BroadcastReceiver(){
        public void onReceive(Context ctxt, Intent intent){
            Toast.makeText(ctxt, "Ummmm...hi!", Toast.LENGTH_LONG).show();
        }
    };

    //a partir da versão 6.0 do Android, pede permissão em
    //em tempo de execução
    public void givePermission(){
        if(!(ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED)) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
        if(!(ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED)) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
    }

    public void openFile(String name){
        File path = Environment.getExternalStoragePublicDirectory(Environment.getExternalStorageDirectory()
                + "/mounted/" + downloadDirectory+turmaId);
        File file = new File(path, name);

        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(Uri.fromFile(file), "application/pdf");
        Intent intent = Intent.createChooser(install, "abrir arquivo");

        try {
            startActivity(intent);
        }catch (ActivityNotFoundException e){
        }
    }

    public void createDirectory(){
        File apkStorage = new File(Environment.getExternalStorageDirectory()
                + "/" + downloadDirectory + turmaId+"/");

        if(!apkStorage.exists()){
            if(apkStorage.mkdir()){
                Log.d("existe","esx");
            }
        }
    }
























    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                startActivityForResult(new Intent(MainActivity.this, ConfigActivity.class), 10);
                break;
            case R.id.credits:
                startActivity(new Intent(MainActivity.this, sobreActivity.class));
                break;
            default:
                break;
        }

        return true;
    }
    //Download de Arquivo


    private class GetFilesURLS extends AsyncTask<String, Integer, String> {
        boolean continuar = true;
        protected String doInBackground(String... urls) {


            URL url = null;
            String content = "";
            try {
                Log.d("url_arquivo",urls[0]);
                url = new URL(urls[0]);

                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.connect();
                BufferedReader rd = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String line;
                while ((line = rd.readLine()) != null) {
                    content += line + "\n";
                }
            } catch (MalformedURLException e) {
                Log.d("requisi1",e.toString());
                e.printStackTrace();
            } catch (ProtocolException e) {
                Log.d("requisi2",e.toString());
                e.printStackTrace();
            } catch (IOException e) {
                Log.d("requisi3",e.toString());
                e.printStackTrace();
            }

            return content;
        }


        protected void onProgressUpdate(Integer... progress) {
        }

        protected void onPostExecute(String result) {
            Log.d("url_arquivo",result);

            try {
                JSONArray jsonarray = new JSONArray(result);
                for (int i = 0; i < jsonarray.length(); i++) {
                    JSONObject jsonobject = jsonarray.getJSONObject(i);
                    String local = jsonobject.getString("local");
                    String siape = jsonobject.getString("siape");
                    String nome = jsonobject.getString("nome");
                    arquivos.add(new Arquivo(local,siape,nome));

                    ItemListView novoItem = new ItemListView("ARQUIVO DE "+siape, nome);
                    novoItem.setFile(true);
                    novoItem.setUrl(IP+"/"+local);
                    listaItensView.add(novoItem);

                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            adaptador = new AdapterListView(MainActivity.this, listaItensView);
                            listaView.setAdapter(adaptador);
                            listaView.setSelection(adaptador.getCount() - 1);
                        }//public void run() {
                    });
                }

            } catch (JSONException e) {
                e.printStackTrace();
                Log.d("url_arquivo",e.toString());
            }



        }


    }


    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public void iniciarServerTethering() {

        Log.d("TaskReceberMSGServer", "INICIANDO STATUS SERVER");

        if (taskReceberMSGServer == null) {
            taskReceberMSGServer = new TaskReceberMSGServer(this);
            taskReceberMSGServer.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
            Log.d("TaskReceberMSGServer", "SERVER JÁ EXECUTANDO");
        }
    }

    public void atualizaLista(final String nome, final String mensagem) {

        MainActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ItemListView novoItem = new ItemListView(nome, mensagem);
                novoItem.setFile(false);
                MainActivity.listaItensView.add(novoItem);
                adaptador = new AdapterListView(MainActivity.this, listaItensView);
                listaView.setAdapter(adaptador);
                listaView.setSelection(adaptador.getCount() - 1);
            }//public void run() {
        });
    }


    class ReceberMSG extends AsyncTask<String, String, List> {

        public int portaServidor = 5555;
        protected ServerSocket soqueteServidor = null;
        protected boolean euServidor = true;

        public ReceberMSG() {
        }

        private boolean abrirSoqueteServidor() {
            try {
                this.soqueteServidor = new ServerSocket(this.portaServidor);
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }

        public void dormir(int tempo) {
            try {
                Thread.sleep(tempo);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        protected List<ItemListView> doInBackground(String... params) {

            while (this.euServidor) {

                Log.d("TaskReceberMSGServer", "ENTREI AQUI 1");

                if (soqueteServidor == null) {
                    if (abrirSoqueteServidor()) {
                    } else {
                        this.euServidor = false;
                    }
                } else {
                    try {
                        pool.execute(new RecMSGSep(this.soqueteServidor.accept()));

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

            }


            return null;
        }
    }


    class EnviarMSG extends AsyncTask<String, String, List> {

        public String nom, msg;

        public EnviarMSG(String nome, String msg) {
            this.nom = nome;
            this.msg = msg;
        }

        @Override
        protected List<ItemListView> doInBackground(String... params) {

                Log.d("ENVIANDO", nome + msg);

                Fuel.get(IP + "/sistema/cadastrar?nome=" + nom + "&mensagem=" + msg+"&turma="+turmaId).timeout(500).responseString(new Handler<String>() {

                    // SEM CONEXAO COM INTERNET, VERIFICA CONEXÃO COM WIFI LOCAL
                    @Override
                    public void failure(Request request, Response response, FuelError error) {

                        INTERNET = false;

                        Log.d("ENV", error.toString());
                        // Nesse caso teremos que verificar se existe conexão WIFI e abrir multicast.
                        String mensg = nom + "656789" + msg;

                        String[] dados = mensg.split("656789");

                        String msgMCNew = dados[0] + dados[1];
                        Log.d("TaskReceberMSGServer ", "FALHA 4");

                        if (!MainActivity.msgsMC.contains(msgMCNew)) {
                            MainActivity.msgsMC.add(msgMCNew);
                            atualizaLista(dados[0], dados[1]);
                        }

                        for (String i : clientesAtuais)
                            pool.execute(new EnvMSGSep(5555, i, mensg));

                    }

                    // CONEXAO COM INTERNET OK
                    @Override
                    public void success(Request request, Response response, String data) {
                       Log.d("ENV", "SUCESSO");
                        INTERNET = true;

                    }
                });

            return null;
        }
    }

    public String getMyIP() {
        WifiManager wifiManager = (WifiManager) MainActivity.this.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        int ips = wifiManager.getConnectionInfo().getIpAddress();
        String ipAddress = Formatter.formatIpAddress(ips);
        return ipAddress;
    }

    public String getServerIP() {
        WifiManager wifiManager = (WifiManager) MainActivity.this.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        int ips = wifiManager.getConnectionInfo().getIpAddress();
        @SuppressWarnings("deprecation")
        String ipAddress = Formatter.formatIpAddress(ips);
        return ipAddress.substring(0, ipAddress.lastIndexOf(".") + 1) + "1";
    }

    public String getIPRede(int ip) {

        if (!gerenciaRedeD2D.iTethering) {
            WifiManager wifiManager = (WifiManager) MainActivity.this.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ips = wifiManager.getConnectionInfo().getIpAddress();
            @SuppressWarnings("deprecation")
            String ipAddress = Formatter.formatIpAddress(ips);
            ipAddress = ipAddress.substring(0, ipAddress.lastIndexOf("."));
            if (!ipAddress.equalsIgnoreCase("0.0.0")) {
                return ipAddress + "." + String.valueOf(ip);
            }
            else {
                ipAddress = "192.168.43.1";
                return ipAddress.substring(0, ipAddress.lastIndexOf(".") + 1) + String.valueOf(ip);
            }
        } else {
            String ipAddress = "192.168.43.1";
            return ipAddress.substring(0, ipAddress.lastIndexOf(".") + 1) + String.valueOf(ip);
        }
    }

    // Esse metodo adiciona uma nova mensagem. Para isso ele pega o texto e envia
    // ao webservice solicitando uma adicao de nova mensagem.
    public void adicionarItem(View botao) {

        if (nome.isEmpty()) {
            Toast.makeText(MainActivity.this, "Você deve definir um nome antes de enviar mensagens!", Toast.LENGTH_SHORT).show();
            obterNome();
        } else {
            EditText campoTexto = (EditText) findViewById(R.id.editText);
            String textoItem = campoTexto.getText().toString();

            ItemListView itemLista = new ItemListView();
            itemLista.setTexto(textoItem);

            String mensagem = textoItem;
            campoTexto.setText("");
            ((EditText) findViewById(R.id.editText)).setHint("Mensagem");
            Log.d("ENVIANDO", mensagem);

            if (mensagem.isEmpty()) {
                Toast.makeText(MainActivity.this, "Digite uma Mensagem!", Toast.LENGTH_SHORT).show();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                new EnviarMSG(nome, mensagem).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            } else {
                new EnviarMSG(nome, mensagem).execute();
            }
        }
    }



    public void iniciaObterClientes() {
        new ObterClientesTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public void checarPermissoes() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (this.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST_FINE_LOCATION);
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        SharedPreferences sharedPref = getSharedPreferences("TextWIn", Context.MODE_PRIVATE);
        this.nome = sharedPref.getString("nome", "SemNome");
        this.IP = sharedPref.getString("ip", IP);
    }

    // Cria um dialogo para o usuario setar no nome dele na aplicacao.
    public void obterNome() {

        SharedPreferences sharedPref = getSharedPreferences("TextWIn", Context.MODE_PRIVATE);
        String retrievedString = sharedPref.getString("nome", "SemNome");

        if (retrievedString.equalsIgnoreCase("SemNome")) {

            final EditText txtUrl = new EditText(this);

            new AlertDialog.Builder(this)
                    .setTitle("Defina seu nome de usuário")
                    .setView(txtUrl)
                    .setPositiveButton("Salvar", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            nome = txtUrl.getText().toString();
                            SharedPreferences sharedPref = getSharedPreferences("TextWIn", Context.MODE_PRIVATE);
                            SharedPreferences.Editor editor = sharedPref.edit();
                            editor.putString("nome", nome);
                            editor.commit();
                        }
                    })
                    .show();
        } else {
            nome = retrievedString;
            Toast.makeText(MainActivity.this, "Bem Vindo ao Chat " + nome, Toast.LENGTH_SHORT).show();
        }


    }


    // Essa AsyncTask e uma Thread que roda em background na aplicacao. O loop dela e infinito
    // e fica atualizando lista de mensagens solicitando o webservice.
    class ObterMensagensTask extends AsyncTask<String, String, List> {

        public boolean continuar = true;

        @Override
        protected List<ItemListView> doInBackground(String... params) {

            //Log.d("ENTROU", "OBTER FUEL");

            while (continuar) {

                try {
                    Triple<Request, Response, Result<byte[], FuelError>> data = Fuel.get(IP + "/sistema/obterMensagens?turma="+turmaId).timeout(1000).response();
                    Request request = data.getFirst();
                    Response response = data.getSecond();
                    Result<byte[], FuelError> text = data.getThird();
                    Log.d("RESPOSTA 1", text.toString());
                    INTERNET = true;

                    byte data2[] = text.component1();
                    int i;
                    for (i = 0; i < data2.length; i++) {
                        if (data2[i] == '\0')
                            break;
                    }

                    // Cria uma nova lista e adiciona todos os itens baixados que estao no data para o listaItensView.
                    try {
                        JSONArray jsonarray = new JSONArray(new String(data2, 0, i, "UTF-8"));
                        for (i = 0; i < jsonarray.length(); i++) {
                            JSONObject jsonobject = jsonarray.getJSONObject(i);
                            String nome = jsonobject.getString("nome");
                            String mensagem = jsonobject.getString("mensagem");

                            String msg = nome + mensagem;

                            if (!msgsMC.contains(msg)) {
                                msgsMC.add(msg);

                                ItemListView novoItem = new ItemListView(nome, mensagem);
                                novoItem.setFile(false);
                                listaItensView.add(novoItem);

                                MainActivity.this.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        adaptador = new AdapterListView(MainActivity.this, listaItensView);
                                        listaView.setAdapter(adaptador);
                                        // listaView.setSelectionFromTop(listaItensView.size(), top);
                                        listaView.setSelection(adaptador.getCount() - 1);
                                    }//public void run() {
                                });
                            }
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                } catch (Exception networkError) {
                   Log.d("FALHOU", networkError.toString());
                    INTERNET = false;

                }
                try {
                    Thread.sleep(2000);
                } catch (Exception e) {

                }
            }

            return null;
        }
    }

    class ObterClientesTask extends AsyncTask<String, String, List> {

        public String IP = "";
        public int porta = 5555;
        public String fim = "";

        @Override
        protected List<ItemListView> doInBackground(String... params) {

            if (ObterclientesAtuais.isEmpty()) {
                for (int i = 1; i <= 253; i++) {
                    this.IP = getIPRede(i);
                   // Log.d("TEXTWIN IP", this.IP);
                    if (!this.IP.equalsIgnoreCase(getMyIP())) {
                        ObterclientesAtuais.add(new ContataCliente(this.porta, this.IP));
                    }
                }
                for (ContataCliente conCli : ObterclientesAtuais) {
                    pool.execute(conCli);
                }
            }
            else {
                for (ContataCliente conCli : ObterclientesAtuais) {
                    conCli.IP = getIPRede(Integer.parseInt(conCli.IP.substring(conCli.IP.lastIndexOf(".") + 1, conCli.IP.length())));
                  //  Log.d("TEXTWIN IP", conCli.IP);
                }
            }
            return null;
        }
    }

    private boolean checkSystemWritePermission() {

        boolean retVal = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            retVal = Settings.System.canWrite(this);
            if (!retVal) {
                MainActivity.this.runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(MainActivity.this, "Permita a Aplicação Alterar o Estado da Sua Rede!", Toast.LENGTH_LONG).show();
                    }
                });

                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                intent.setData(Uri.parse("package:" + MainActivity.this.getPackageName()));
                startActivity(intent);
            }
        }
        return retVal;
    }

    class ContataCliente implements Runnable {
        String IP;
        int porta = 5555;
        ConexaoCliente conexao = null;
        boolean continuar = true;

        ContataCliente(int porta, String IP) {
            this.porta = porta;
            this.IP = IP;
        }

        public void run() {

            while (continuar) {
                try {
                    conexao = new ConexaoCliente(this.IP, this.porta);

                    if (conexao.conectaServidor()) {

                        if (!clientesAtuais.contains(this.IP))
                            clientesAtuais.add(this.IP);
                        conexao.desconectaServidor();
                    } else {
                        if (clientesAtuais.contains(this.IP))
                            clientesAtuais.remove(this.IP);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                dormir(3000);
            }
        }
    }

    public void dormir(int tempo) {
        try {
            Thread.sleep(tempo);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    class EnvMSGSep implements Runnable {
        int porta = 5555;
        String IP = "";
        String mensagem = "";
        ConexaoCliente conexao = null;

        EnvMSGSep(int porta, String IP, String mensagem) {
            this.porta = porta;
            this.IP = IP;
            this.mensagem = mensagem;
            Log.d("TaskEnviarMSGServer", this.IP + " - " + this.porta + " - " + this.mensagem);
        }

        public void run() {
            conexao = new ConexaoCliente(this.IP, this.porta);

            if (conexao.conectaServidor()) {
                Log.d("TaskEnviarMSGServer", "CONECTADO AO SERVER");
                try {
                    conexao.getEnviaDados().writeObject(this.mensagem);
                    conexao.getEnviaDados().flush();
                    Log.d("TaskEnviarMSGServer", "enviado mensagem");
                    conexao.desconectaServidor();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    class RecMSGSep implements Runnable {
        Socket soqueteCliente = null;
        ObjectOutputStream enviaDados;
        ObjectInputStream recebeDados;

        RecMSGSep(Socket soqueteCliente) {
            this.soqueteCliente = soqueteCliente;
        }

        public void run() {
            if (soqueteCliente != null) {

                try {
                    String endIP = soqueteCliente.getRemoteSocketAddress().toString();
                    endIP = endIP.substring(1, endIP.lastIndexOf(":"));
                    if (!clientesAtuais.contains(endIP))
                        clientesAtuais.add(endIP);

                    enviaDados = new ObjectOutputStream(soqueteCliente.getOutputStream());
                    recebeDados = new ObjectInputStream(soqueteCliente.getInputStream());

                    String msg = (String) recebeDados.readObject();

                    Log.d("TaskReceberMSGServer ", msg);
                    String[] dados = msg.split("656789");

                    String msgMCNew = dados[0] + dados[1];
                    Log.d("TaskReceberMSGServer ", "FALHA 4");

                    if (!MainActivity.msgsMC.contains(msgMCNew)) {
                        MainActivity.msgsMC.add(msgMCNew);
                        atualizaLista(dados[0], dados[1]);
                    }

                    enviaDados.close();
                    recebeDados.close();
                    soqueteCliente.close();
                } catch (Exception e) {
                    //e.printStackTrace();
                }

            }
        }
    }
}
