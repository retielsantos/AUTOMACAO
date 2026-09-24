// ==================================================================
// JCI_SEQ - Port para Niagara N4 Program Object (VERSAO 5 DISPOSITIVOS)
// Baseado em: JCI_SEQ_V0_8_S7-1200.SCL
// Expandido de 4 para 5 bombas - mesma logica, mesmos nomes de slot
// padrao (DeviceX...), com DeviceX indo de 1 a 5.
//
// COMO USAR:
// 1. Arraste um "Program" (palette "program") para o wiresheet.
// 2. Na aba "Slots", crie os slots listados no arquivo
//    JCI_SEQ_N4_SlotList.md (mesmos nomes usados abaixo).
// 3. Cole TODO o conteudo deste arquivo na aba "Edit" (substituindo
//    o onStart/onExecute/onStop padrao).
// 4. Salve e compile (icone do martelo).
//
// IMPORTANTE - GATILHO DE EXECUCAO:
// O SCL roda em ciclo fixo (scan da CPU). O Program object do N4 so
// executa onExecute() quando um slot com flag "Execute On Change"
// muda de valor. Para que os timers (MinOn/MinOff/Interstage) sejam
// reavaliados mesmo sem mudanca de Demand, ligue um "heartbeat" de
// 1s (ex.: kitControl:Ramp, ou um Clock ticking) num slot dedicado
// (ex.: "Heartbeat", tipo StatusNumeric) marcado como Execute On
// Change. Todos os slots de comando (Enable, Rank, RotateNow,
// InstantShutdown, Input) tambem devem ter Execute On Change.
//
// DIFERENCAS DE PROPOSITO EM RELACAO AO SCL ORIGINAL:
// - Timers usam tempo de relogio real (System.currentTimeMillis())
//   em vez de contagem de scan, pois o onExecute nao roda em ciclo
//   fixo. Efeito pratico: igual ou mais preciso que o TON original.
// - ActualOut = eco do proprio comando (DeviceXOut), exatamente
//   como no SCL (ActualOut := DeviceXOut). Se no futuro quiser
//   usar status real de campo (DI), troque a leitura da Secao 1
//   (ver comentario "TROCAR AQUI PARA STATUS REAL").
// - Sem retain: no onStart() tudo volta para CurrentStage = 0,
//   igual a um reboot/cold start da CPU S7.
// ==================================================================

// ------------------------------------------------------------
// Timer auxiliar (equivalente ao TON do SCL): Q fica TRUE quando
// IN permanece TRUE por >= PT (em milissegundos).
// ------------------------------------------------------------
private static class SimpleTon
{
  private long startMillis = -1L;
  boolean q;

  void update(boolean in, long ptMillis)
  {
    long now = System.currentTimeMillis();
    if (in)
    {
      if (startMillis < 0) startMillis = now;
      q = (now - startMillis) >= ptMillis;
    }
    else
    {
      startMillis = -1L;
      q = false;
    }
  }
}

// ------------------------------------------------------------
// Estado persistente (equivalente ao VAR do SCL - estas variaveis
// NAO sao reinicializadas a cada onExecute, apenas no onStart)
// ------------------------------------------------------------
private boolean[] previousOut   = new boolean[6]; // indices 1..5
private boolean[] minOffBypass  = new boolean[6]; // indices 1..5

private boolean lastRotateNow;

private int currentStage;

private boolean interstageOnActive;
private boolean interstageOffActive;

private final SimpleTon tMinOn1 = new SimpleTon();
private final SimpleTon tMinOn2 = new SimpleTon();
private final SimpleTon tMinOn3 = new SimpleTon();
private final SimpleTon tMinOn4 = new SimpleTon();
private final SimpleTon tMinOn5 = new SimpleTon();

private final SimpleTon tMinOff1 = new SimpleTon();
private final SimpleTon tMinOff2 = new SimpleTon();
private final SimpleTon tMinOff3 = new SimpleTon();
private final SimpleTon tMinOff4 = new SimpleTon();
private final SimpleTon tMinOff5 = new SimpleTon();

private final SimpleTon tInterstageOn  = new SimpleTon();
private final SimpleTon tInterstageOff = new SimpleTon();


// ==================================================================
// onStart - equivalente ao cold start da CPU (CurrentStage = 0)
// ==================================================================
public void onStart() throws Exception
{
  for (int i = 1; i <= 5; i++)
  {
    previousOut[i]  = false;
    minOffBypass[i] = true;   // primeira partida nunca e bloqueada por Min Off
  }

  lastRotateNow        = false;
  currentStage         = 0;
  interstageOnActive   = false;
  interstageOffActive  = false;

  setDevice1Out(new javax.baja.status.BStatusBoolean(false));
  setDevice2Out(new javax.baja.status.BStatusBoolean(false));
  setDevice3Out(new javax.baja.status.BStatusBoolean(false));
  setDevice4Out(new javax.baja.status.BStatusBoolean(false));
  setDevice5Out(new javax.baja.status.BStatusBoolean(false));

  setCurrentStage(new javax.baja.status.BStatusNumeric(0));
  setOperatingState(new javax.baja.status.BStatusNumeric(0));
  setInterstageTiming(new javax.baja.status.BStatusBoolean(false));
}


// ==================================================================
// onExecute - equivalente ao corpo do FUNCTION_BLOCK
// ==================================================================
public void onExecute() throws Exception
{
  // ------------------------------------------------------------
  // 1 - COPIA ENTRADAS
  // ------------------------------------------------------------
  boolean[] enable = new boolean[6];
  int[]     rank   = new int[6];
  boolean[] actualOut = new boolean[6]; // TROCAR AQUI PARA STATUS REAL se necessario

  enable[1] = getDevice1Enable().getValue();
  enable[2] = getDevice2Enable().getValue();
  enable[3] = getDevice3Enable().getValue();
  enable[4] = getDevice4Enable().getValue();
  enable[5] = getDevice5Enable().getValue();

  rank[1] = (int) Math.round(getDevice1Rank().getValue());
  rank[2] = (int) Math.round(getDevice2Rank().getValue());
  rank[3] = (int) Math.round(getDevice3Rank().getValue());
  rank[4] = (int) Math.round(getDevice4Rank().getValue());
  rank[5] = (int) Math.round(getDevice5Rank().getValue());

  // ActualOut = eco do proprio comando, igual ao SCL original
  actualOut[1] = getDevice1Out().getValue();
  actualOut[2] = getDevice2Out().getValue();
  actualOut[3] = getDevice3Out().getValue();
  actualOut[4] = getDevice4Out().getValue();
  actualOut[5] = getDevice5Out().getValue();

  boolean instantShutdown = getInstantShutdown().getValue();
  boolean rotateNow       = getRotateNow().getValue();

  double makeLimit1 = getMakeLimit1().getValue();
  double makeLimit2 = getMakeLimit2().getValue();
  double makeLimit3 = getMakeLimit3().getValue();
  double makeLimit4 = getMakeLimit4().getValue();
  double makeLimit5 = getMakeLimit5().getValue();

  double breakLimit1 = getBreakLimit1().getValue();
  double breakLimit2 = getBreakLimit2().getValue();
  double breakLimit3 = getBreakLimit3().getValue();
  double breakLimit4 = getBreakLimit4().getValue();
  double breakLimit5 = getBreakLimit5().getValue();

  long minOnMs             = getMinOnTime().getMillis();
  long minOffMs            = getMinOffTime().getMillis();
  long interstageOnDelayMs  = getInterstageOnDelay().getMillis();
  long interstageOffDelayMs = getInterstageOffDelay().getMillis();

  // ------------------------------------------------------------
  // 2 - ESTADO ANTERIOR
  // ------------------------------------------------------------
  int previousStage = currentStage;

  // ------------------------------------------------------------
  // 3 - DETECTA TRANSICOES OFF PARA INICIAR MIN OFF
  // ------------------------------------------------------------
  for (int i = 1; i <= 5; i++)
  {
    if (previousOut[i] && !actualOut[i])
    {
      minOffBypass[i] = false;
    }
    previousOut[i] = actualOut[i];
  }

  // ------------------------------------------------------------
  // 4 - PULSO DE ROTATE NOW
  // ------------------------------------------------------------
  boolean rotatePulse = rotateNow && !lastRotateNow;
  lastRotateNow = rotateNow;

  // ------------------------------------------------------------
  // 5 - DEMAND
  //     Input ja vem tratado/normalizado por um PID ou logica a
  //     montante (a ser combinado direto com Make/Break) - sem
  //     clamp ou normalizacao aqui.
  // ------------------------------------------------------------
  double demand = getInput().getValue();

  // ------------------------------------------------------------
  // 6 - CONTA HABILITADOS (mantido por paridade com o SCL;
  //     nao e usado diretamente nas decisoes abaixo)
  // ------------------------------------------------------------
  int enabledCount = 0;
  for (int i = 1; i <= 5; i++) if (enable[i]) enabledCount++;

  // ------------------------------------------------------------
  // 7 - ORDENA POR RANK (BaseOrder)
  //     Menor Rank = maior prioridade; empate = menor numero.
  // ------------------------------------------------------------
  int[] baseOrder = {0, 1, 2, 3, 4, 5};

  for (int i = 1; i <= 4; i++)
  {
    for (int j = i + 1; j <= 5; j++)
    {
      boolean swap = false;
      if (rank[baseOrder[j]] < rank[baseOrder[i]])
      {
        swap = true;
      }
      else if (rank[baseOrder[j]] == rank[baseOrder[i]] && baseOrder[j] < baseOrder[i])
      {
        swap = true;
      }
      if (swap)
      {
        int tmp = baseOrder[i];
        baseOrder[i] = baseOrder[j];
        baseOrder[j] = tmp;
      }
    }
  }

  // ------------------------------------------------------------
  // 8 - REORDENACAO DINAMICA (Order):
  //     1) habilitado+ativo  2) habilitado+inativo  3) desabilitado
  // ------------------------------------------------------------
  int[] order = new int[6];
  int k = 0;

  for (int j = 1; j <= 5; j++)
  {
    int d = baseOrder[j];
    if (enable[d] && actualOut[d]) order[++k] = d;
  }
  for (int j = 1; j <= 5; j++)
  {
    int d = baseOrder[j];
    if (enable[d] && !actualOut[d]) order[++k] = d;
  }
  for (int j = 1; j <= 5; j++)
  {
    int d = baseOrder[j];
    if (!enable[d]) order[++k] = d;
  }

  // ------------------------------------------------------------
  // 9 - TIMERS MIN ON
  // ------------------------------------------------------------
  tMinOn1.update(actualOut[1], minOnMs);
  tMinOn2.update(actualOut[2], minOnMs);
  tMinOn3.update(actualOut[3], minOnMs);
  tMinOn4.update(actualOut[4], minOnMs);
  tMinOn5.update(actualOut[5], minOnMs);

  boolean minOnOK1 = (minOnMs == 0) || tMinOn1.q;
  boolean minOnOK2 = (minOnMs == 0) || tMinOn2.q;
  boolean minOnOK3 = (minOnMs == 0) || tMinOn3.q;
  boolean minOnOK4 = (minOnMs == 0) || tMinOn4.q;
  boolean minOnOK5 = (minOnMs == 0) || tMinOn5.q;

  // ------------------------------------------------------------
  // 10 - TIMERS MIN OFF (bypass ate a primeira transicao ON->OFF)
  // ------------------------------------------------------------
  tMinOff1.update(!minOffBypass[1] && !actualOut[1], minOffMs);
  tMinOff2.update(!minOffBypass[2] && !actualOut[2], minOffMs);
  tMinOff3.update(!minOffBypass[3] && !actualOut[3], minOffMs);
  tMinOff4.update(!minOffBypass[4] && !actualOut[4], minOffMs);
  tMinOff5.update(!minOffBypass[5] && !actualOut[5], minOffMs);

  boolean minOffOK1 = minOffBypass[1] || (minOffMs == 0) || tMinOff1.q;
  boolean minOffOK2 = minOffBypass[2] || (minOffMs == 0) || tMinOff2.q;
  boolean minOffOK3 = minOffBypass[3] || (minOffMs == 0) || tMinOff3.q;
  boolean minOffOK4 = minOffBypass[4] || (minOffMs == 0) || tMinOff4.q;
  boolean minOffOK5 = minOffBypass[5] || (minOffMs == 0) || tMinOff5.q;

  // ------------------------------------------------------------
  // 11 - INTERSTAGE TIMERS
  // ------------------------------------------------------------
  tInterstageOn.update(interstageOnActive, interstageOnDelayMs);
  tInterstageOff.update(interstageOffActive, interstageOffDelayMs);

  boolean interstageOnJustCleared  = false;
  boolean interstageOffJustCleared = false;

  if (interstageOnActive && ((interstageOnDelayMs == 0) || tInterstageOn.q))
  {
    interstageOnActive = false;
    interstageOnJustCleared = true;
  }
  if (interstageOffActive && ((interstageOffDelayMs == 0) || tInterstageOff.q))
  {
    interstageOffActive = false;
    interstageOffJustCleared = true;
  }

  // ------------------------------------------------------------
  // 12 - STAGE UP / STAGE DOWN REQUEST
  // ------------------------------------------------------------
  boolean stageUpRequest = false;
  boolean stageDownRequest = false;

  if (currentStage < 5)
  {
    if      (currentStage == 0 && demand >= makeLimit1) stageUpRequest = true;
    else if (currentStage == 1 && demand >= makeLimit2) stageUpRequest = true;
    else if (currentStage == 2 && demand >= makeLimit3) stageUpRequest = true;
    else if (currentStage == 3 && demand >= makeLimit4) stageUpRequest = true;
    else if (currentStage == 4 && demand >= makeLimit5) stageUpRequest = true;
  }

  if (currentStage > 0)
  {
    if      (currentStage == 1 && demand <= breakLimit1) stageDownRequest = true;
    else if (currentStage == 2 && demand <= breakLimit2) stageDownRequest = true;
    else if (currentStage == 3 && demand <= breakLimit3) stageDownRequest = true;
    else if (currentStage == 4 && demand <= breakLimit4) stageDownRequest = true;
    else if (currentStage == 5 && demand <= breakLimit5) stageDownRequest = true;
  }

  // ------------------------------------------------------------
  // 13 - PROXIMO EQUIPAMENTO PARA UP/DOWN
  // ------------------------------------------------------------
  int upDevice = 0;
  int downDevice = 0;

  for (int i = 1; i <= 5; i++)
  {
    if (order[i] > 0 && enable[order[i]] && !actualOut[order[i]])
    {
      upDevice = order[i];
      break;
    }
  }
  for (int i = 1; i <= 5; i++)
  {
    if (order[i] > 0 && enable[order[i]] && actualOut[order[i]])
    {
      downDevice = order[i]; // ultimo da lista = maior Rank ativo
    }
  }

  // ------------------------------------------------------------
  // 14 - EXECUTA STAGE UP
  // ------------------------------------------------------------
  boolean stageChangedThisScan = false;

  if (stageUpRequest)
  {
    if (interstageOffActive) interstageOffActive = false;

    if (!interstageOnActive && !interstageOnJustCleared)
    {
      if (upDevice == 0)
      {
        currentStage++;
        stageChangedThisScan = true;
      }
      else
      {
        boolean ok = (upDevice == 1 && minOffOK1)
                  || (upDevice == 2 && minOffOK2)
                  || (upDevice == 3 && minOffOK3)
                  || (upDevice == 4 && minOffOK4)
                  || (upDevice == 5 && minOffOK5);
        if (ok)
        {
          currentStage++;
          stageChangedThisScan = true;
        }
      }
      if (stageChangedThisScan) interstageOnActive = true;
    }
  }

  // ------------------------------------------------------------
  // 15 - EXECUTA STAGE DOWN
  // ------------------------------------------------------------
  if (stageDownRequest && !stageChangedThisScan)
  {
    if (interstageOnActive) interstageOnActive = false;

    if (!interstageOffActive && !interstageOffJustCleared)
    {
      if (downDevice == 0)
      {
        currentStage--;
        stageChangedThisScan = true;
      }
      else
      {
        boolean ok = (downDevice == 1 && minOnOK1)
                  || (downDevice == 2 && minOnOK2)
                  || (downDevice == 3 && minOnOK3)
                  || (downDevice == 4 && minOnOK4)
                  || (downDevice == 5 && minOnOK5);
        if (ok)
        {
          currentStage--;
          stageChangedThisScan = true;
        }
      }
      if (stageChangedThisScan) interstageOffActive = true;
    }
  }

  // ------------------------------------------------------------
  // 16 - LIMITA CURRENT STAGE
  // ------------------------------------------------------------
  if (currentStage < 0) currentStage = 0;
  if (currentStage > 5) currentStage = 5;

  // ------------------------------------------------------------
  // 17 - SELECAO NORMAL (refeita todo scan -> retry automatico)
  // ------------------------------------------------------------
  boolean[] desiredOut = new boolean[6];

  for (int stg = 1; stg <= 5; stg++)
  {
    if (currentStage >= stg && order[stg] > 0 && enable[order[stg]])
    {
      desiredOut[order[stg]] = true;
    }
  }

  // ------------------------------------------------------------
  // 18 - DEVICE DISABLED (desliga na hora, ignora Min On)
  // ------------------------------------------------------------
  for (int i = 1; i <= 5; i++)
  {
    if (!enable[i]) desiredOut[i] = false;
  }

  // ------------------------------------------------------------
  // 19 - MIN OFF (bloqueia nova partida)
  // ------------------------------------------------------------
  if (desiredOut[1] && !actualOut[1] && !minOffOK1) desiredOut[1] = false;
  if (desiredOut[2] && !actualOut[2] && !minOffOK2) desiredOut[2] = false;
  if (desiredOut[3] && !actualOut[3] && !minOffOK3) desiredOut[3] = false;
  if (desiredOut[4] && !actualOut[4] && !minOffOK4) desiredOut[4] = false;
  if (desiredOut[5] && !actualOut[5] && !minOffOK5) desiredOut[5] = false;

  // ------------------------------------------------------------
  // 20 - MIN ON (bloqueia desligamento; excecoes: Disabled/InstantShutdown)
  // ------------------------------------------------------------
  if (enable[1] && !instantShutdown && !desiredOut[1] && actualOut[1] && !minOnOK1) desiredOut[1] = true;
  if (enable[2] && !instantShutdown && !desiredOut[2] && actualOut[2] && !minOnOK2) desiredOut[2] = true;
  if (enable[3] && !instantShutdown && !desiredOut[3] && actualOut[3] && !minOnOK3) desiredOut[3] = true;
  if (enable[4] && !instantShutdown && !desiredOut[4] && actualOut[4] && !minOnOK4) desiredOut[4] = true;
  if (enable[5] && !instantShutdown && !desiredOut[5] && actualOut[5] && !minOnOK5) desiredOut[5] = true;

  // ------------------------------------------------------------
  // 21 - ROTATE NOW
  // ------------------------------------------------------------
  if (rotatePulse && !instantShutdown && currentStage > 0)
  {
    int rotateOut = 0;
    for (int i = 1; i <= 5; i++)
    {
      if (enable[i] && actualOut[i])
      {
        if (rotateOut == 0)
        {
          rotateOut = i;
        }
        else if (rank[i] > rank[rotateOut])
        {
          rotateOut = i;
        }
        else if (rank[i] == rank[rotateOut] && i > rotateOut)
        {
          rotateOut = i;
        }
      }
    }

    int rotatePos = 0;
    if (rotateOut > 0)
    {
      for (int i = 1; i <= 5; i++)
      {
        if (baseOrder[i] == rotateOut) rotatePos = i;
      }
    }

    int rotateIn = 0;
    if (rotatePos > 0)
    {
      for (int c = 1; c <= 5; c++)
      {
        int candidatePos = rotatePos + c;
        if (candidatePos > 5) candidatePos -= 5;

        int d = baseOrder[candidatePos];
        if (enable[d] && !actualOut[d])
        {
          rotateIn = d;
          break;
        }
      }
    }

    if (rotateOut > 0 && rotateIn > 0)
    {
      desiredOut[rotateOut] = false;
      desiredOut[rotateIn]  = true;
    }
  }

  // ------------------------------------------------------------
  // 22 - INSTANT SHUTDOWN
  // ------------------------------------------------------------
  if (instantShutdown)
  {
    desiredOut[1] = desiredOut[2] = desiredOut[3] = desiredOut[4] = desiredOut[5] = false;
    currentStage = 0;
    interstageOnActive = false;
    interstageOffActive = false;
    tInterstageOn.update(false, interstageOnDelayMs);
    tInterstageOff.update(false, interstageOffDelayMs);
  }

  // ------------------------------------------------------------
  // 23 - ATUALIZA SAIDAS
  // ------------------------------------------------------------
  getDevice1Out().setValue(desiredOut[1]);
  getDevice2Out().setValue(desiredOut[2]);
  getDevice3Out().setValue(desiredOut[3]);
  getDevice4Out().setValue(desiredOut[4]);
  getDevice5Out().setValue(desiredOut[5]);

  // ------------------------------------------------------------
  // 24 - INTERSTAGE TIMING
  // ------------------------------------------------------------
  getInterstageTiming().setValue(interstageOnActive || interstageOffActive);

  // ------------------------------------------------------------
  // 25 - OPERATING STATE (0=Idle 1=Starting 2=Running 3=Stopping)
  // ------------------------------------------------------------
  int operatingState;
  if (instantShutdown)               operatingState = 0;
  else if (currentStage == 0)        operatingState = 0;
  else if (currentStage > previousStage) operatingState = 1;
  else if (currentStage < previousStage) operatingState = 3;
  else                                operatingState = 2;

  getOperatingState().setValue(operatingState);
  getCurrentStage().setValue(currentStage);
}


// ==================================================================
// onStop
// ==================================================================
public void onStop() throws Exception
{
  // nada a fazer - sem retain, proximo onStart reinicia do Stage 0
}
