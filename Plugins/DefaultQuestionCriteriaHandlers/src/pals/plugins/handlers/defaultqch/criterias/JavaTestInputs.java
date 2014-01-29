package pals.plugins.handlers.defaultqch.criterias;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import pals.base.Logging;
import pals.base.NodeCore;
import pals.base.Storage;
import pals.base.UUID;
import pals.base.assessment.InstanceAssignment;
import pals.base.assessment.InstanceAssignmentCriteria;
import pals.base.assessment.InstanceAssignmentQuestion;
import pals.base.assessment.QuestionCriteria;
import pals.base.database.Connector;
import pals.base.utils.PalsProcess;
import pals.base.web.RemoteRequest;
import pals.base.web.WebRequestData;
import pals.base.web.security.CSRF;
import pals.plugins.handlers.defaultqch.DefaultQC;
import pals.plugins.handlers.defaultqch.data.CodeJava_Instance;
import pals.plugins.handlers.defaultqch.data.CodeJava_Question;
import pals.plugins.handlers.defaultqch.data.JavaTestInputs_Criteria;
import pals.plugins.handlers.defaultqch.data.JavaTestInputs_InstanceCriteria;
import pals.plugins.handlers.defaultqch.java.CompilerResult;
import pals.plugins.handlers.defaultqch.java.Utils;
import pals.plugins.handlers.defaultqch.questions.CodeJava;

/**
 * Handles text inputs criteria marking.
 */
public class JavaTestInputs
{
    // Constants ***************************************************************
    public static final UUID UUID_CTYPE = UUID.parse("49d5b5fa-e0b9-427a-8171-04e7ae33fe64");
    // Methods *****************************************************************
    public static boolean pageCriteriaEdit(WebRequestData data, QuestionCriteria qc)
    {
        // Load criteria data
        JavaTestInputs_Criteria cdata = (JavaTestInputs_Criteria)qc.getData();
        if(cdata == null)
            cdata = new JavaTestInputs_Criteria();
        // Check for postback
        RemoteRequest req = data.getRequestData();
        String critTitle = req.getField("crit_title");
        String critWeight = req.getField("crit_weight");
        String critClassName = req.getField("crit_class_name");
        String critMethod = req.getField("crit_method");
        String critTestCode = req.getField("crit_test_code");
        String critInputTypes = req.getField("crit_input_types");
        String critInputs = req.getField("crit_inputs");
        String critForceCompile = req.getField("crit_force_compile");
        if(critTitle != null && critWeight != null && critClassName != null && critMethod != null && critTestCode != null && critInputTypes != null && critInputs != null)
        {
            boolean compile = !critTestCode.equals(cdata.getTestCode()) || (critForceCompile != null && critForceCompile.equals("1"));
            if(compile)
            {
                String className = Utils.parseFullClassName(critTestCode);
                if(className == null)
                    data.setTemplateData("error", "Cannot compile test-code, unable to determine the full class-name.");
                else if(!className.equals(critClassName))
                    data.setTemplateData("error", "Class-name of test-code ('"+className+"') does not match the provided class-name ('"+critClassName+"'); these must be the same.");
                else
                {
                    HashMap<String,String> codeMap = new HashMap<>();
                    codeMap.put(className, critTestCode);
                    // Attempt to compile the code
                    CompilerResult cr = Utils.compile(data.getCore(), Storage.getPath_tempQC(data.getCore().getPathShared(), qc), codeMap);
                    CompilerResult.CompileStatus cs = cr.getStatus();
                    switch(cs)
                    {
                        case Unknown:
                            data.setTemplateData("warning", cs.getText());
                            break;
                        case Failed_CompilerNotFound:
                        case Failed_TempDirectory:
                        case Failed:
                            data.setTemplateData("error", cs.getText());
                            data.setTemplateData("error_messages", cr.getCodeErrors());
                            break;
                        case Success:
                            data.setTemplateData("info", cs.getText());
                            break;
                    }
                }
            }
            // Update data model
            cdata.setClassName(critClassName);
            cdata.setMethod(critMethod);
            cdata.setTestCode(critTestCode);
            if(!cdata.setInputTypes(critInputTypes))
                data.setTemplateData("error", "Invalid input-types.");
            else if(!cdata.setInputs(critInputs))
                data.setTemplateData("error", "Invalid inputs.");
            else
            {
                // Handle entire process
                CriteriaHelper.handle_criteriaEditPostback(data, qc, critTitle, critWeight, cdata);
            }
        }
        // Setup the page
        data.setTemplateData("pals_title", "Admin - Questions - Edit Criteria");
        data.setTemplateData("pals_content", "defaultqch/criteria/javatestinputs_edit");
        Utils.pageHookCodeMirror_Java(data);
        // -- Fields
        data.setTemplateData("criteria", qc);
        data.setTemplateData("question", qc.getQuestion());
        data.setTemplateData("csrf", CSRF.set(data));
        data.setTemplateData("crit_title", critTitle != null ? critTitle : qc.getTitle());
        data.setTemplateData("crit_weight", critWeight != null ? critWeight : qc.getWeight());
        data.setTemplateData("crit_class_name", critClassName != null ? critClassName : cdata.getClassName());
        data.setTemplateData("crit_method", critMethod != null ? critMethod : cdata.getMethod());
        data.setTemplateData("crit_test_code", critTestCode != null ? critTestCode : cdata.getTestCode());
        data.setTemplateData("crit_input_types", critInputTypes != null ? critInputTypes : cdata.getInputTypesWeb());
        data.setTemplateData("crit_inputs", critInputs != null ? critInputs : cdata.getInputsWeb());
        return true;
    }
    public static boolean criteriaMarking(Connector conn, NodeCore core, InstanceAssignmentCriteria iac)
    {
        if(!iac.getIAQ().isAnswered())
            iac.setMark(0);
        else
        {
            // Load idata, qdata and cdata
            CodeJava_Instance       idata = (CodeJava_Instance)iac.getIAQ().getData();
            CodeJava_Question       qdata = (CodeJava_Question)iac.getQC().getQuestion().getData();
            JavaTestInputs_Criteria cdata = (JavaTestInputs_Criteria)iac.getQC().getData();
            if(idata == null || idata.getStatus() != CompilerResult.CompileStatus.Success)
            {
                iac.setMark(0);     // No answer data; no need to mark.
                System.err.println("no data.");
            }
            else if(qdata == null || cdata == null || cdata.getInputs().length == 0)
                return false;       // Question or criteria has not been setup properly.
            else
            {
                System.err.println("inside test.");
                // Fetch path of compiled classes
                String      pathQC = Storage.getPath_tempQC(core.getPathShared(), iac.getQC());
                String      pathIAQ = Storage.getPath_tempIAQ(core.getPathShared(), iac.getIAQ());
                // Fetch data ready for tests
                String[]    types = cdata.getInputTypes();
                String[][]  inputs = cdata.getInputs();
                String[]    whiteList = qdata.getWhitelist();
                String      className = cdata.getClassName();
                String      method = cdata.getMethod();
                int         timeout = core.getSettings().getInt("tools/windows_user_tool/timeout_ms", 12000);
                int         timeoutJS = core.getSettings().getInt("tools/javasandbox/timeout_ms", 10000);
                String      javaSandbox;
                try
                {
                    javaSandbox = new File(core.getSettings().getStr("tools/javasandbox/path")).getCanonicalPath();
                    pathQC = new File(pathQC).getCanonicalPath();
                    pathIAQ = new File(pathIAQ).getCanonicalPath();
                }
                catch(IOException ex)
                {
                    core.getLogging().log(DefaultQC.LOGGING_ALIAS, "Failed to create paths for Java-Sandbox/pathQC/pathIAQ ~ aiqid "+iac.getIAQ().getAIQID()+", qcid "+iac.getQC().getQCID(), Logging.EntryType.Warning);
                    iac.setStatus(InstanceAssignmentCriteria.Status.AwaitingManualMarking);
                    return iac.persist(conn) == InstanceAssignmentCriteria.PersistStatus.Success;
                }
                // Iterate each test input; test with student's and lecturer's code
                JavaTestInputs_InstanceCriteria icdata = new JavaTestInputs_InstanceCriteria(inputs.length);
                String argsQC, argsIAQ;
                String valQC, valIAQ;
                int correct = 0;
                String[] formattedInputs;
                
                System.err.println("DEBUG ~ java sb ~ "+javaSandbox);
                
                for(int row = 0; row < inputs.length; row++)
                {
                    // Format inputs
                    formattedInputs = inputs[row];
                    // Build args for both
                    argsQC = Utils.buildJavaSandboxArgs(javaSandbox, pathQC, className, method, whiteList, true, timeoutJS, types, formattedInputs);
                    argsIAQ = Utils.buildJavaSandboxArgs(javaSandbox, pathIAQ, className, method, whiteList, true, timeoutJS, types, formattedInputs);
                    
                    System.err.println("DEBUG ~ argsQC ~ '"+argsQC+"'");
                    System.err.println("DEBUG ~ argsIAQ ~ '"+argsIAQ+"'");
                    
                    // Execute each process and capture output
                    valQC = run(PalsProcess.create(core, "java", argsQC), timeout);
                    valIAQ = run(PalsProcess.create(core, "java", argsIAQ), timeout);
                    
                    
                    System.err.println("DEBUG ~ valQC ~ '"+valQC+"'");
                    System.err.println("DEBUG ~ valIAQ ~ '"+valIAQ+"'");
                    
                    // Compare
                    if(valIAQ == null || valQC == null)
                    {
                        // Something has gone wrong, set to manual marking
                        System.err.println("DEBUG ~ failed null.");
                        iac.setStatus(InstanceAssignmentCriteria.Status.AwaitingManualMarking);
                        return iac.persist(conn) == InstanceAssignmentCriteria.PersistStatus.Success;
                    }
                    else if(valQC.equals(valIAQ))
                    {
                        correct++;
                        icdata.setCorrect(row, true);
                    }
                    else
                        icdata.setCorrect(row, false);
                    // Update output
                    icdata.setInput(row, inputsToStr(formattedInputs));
                    icdata.setOutput(row, valIAQ);
                }
                // Update data model for feedback
                iac.setData(icdata);
                // Calculate score
                iac.setMark( (int)(((double)correct/(double)inputs.length)*100.0) );
                
                System.err.println("DEBUG ~ successfully finished.");
            }
        }
        iac.setStatus(InstanceAssignmentCriteria.Status.Marked);
        return iac.persist(conn) == InstanceAssignmentCriteria.PersistStatus.Success;
    }
    private static String inputsToStr(String[] inputs)
    {
        if(inputs.length == 0)
            return "";
        StringBuilder sb = new StringBuilder();
        for(String s : inputs)
            sb.append(s).append(',');
        return sb.deleteCharAt(sb.length()-1).toString();
    }
    private static String run(PalsProcess proc, int timeout)
    {
        // Start the process
        if(!proc.start())
            return null;
        // Begin reading standard output
        StringBuilder buffer = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(proc.getProcess().getInputStream()));
        // Wait for the process to terminate
        long timeoutL = (long)timeout;
        long start = System.currentTimeMillis();
        char[] cbuffer = new char[1024];
        int cbufferRead;
        while(!proc.hasExited())
        {
            try
            {
                // Check if to kill the process
                if(System.currentTimeMillis()-start > timeoutL)
                {
                    proc.getProcess().destroy();
                    break;
                }
                // Read more output
                try
                {
                    while((cbufferRead = br.read(cbuffer)) != -1)
                        buffer.append(cbuffer, 0, cbufferRead);
                }
                catch(IOException ex)
                {
                }
                // Sleep...
                Thread.sleep(10);
            }
            catch(InterruptedException ex) {}
        }
        return buffer.toString().trim();
    }
    public static boolean criteriaDisplay(WebRequestData data, InstanceAssignment ia, InstanceAssignmentQuestion iaq, InstanceAssignmentCriteria iac, StringBuilder html)
    {
        // Load icdata
        JavaTestInputs_InstanceCriteria icdata = (JavaTestInputs_InstanceCriteria)iac.getData();
        if(icdata != null)
        {
            HashMap<String,Object> kvs = new HashMap<>();
            kvs.put("result", icdata);
            kvs.put("mark", iac.getMark());
            kvs.put("input_mark", (1.0/icdata.getTests())*100.0);
            html.append(data.getCore().getTemplates().render(data, kvs, "defaultqch/criteria/javatestinputs_display"));
            return true;
        }
        return false;
    }
}