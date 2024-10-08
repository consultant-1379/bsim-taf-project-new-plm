package com.ericsson.oss.bsim.operators.api;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Scanner;

import org.apache.log4j.Logger;

import com.ericsson.cifwk.taf.ApiOperator;
import com.ericsson.cifwk.taf.osgi.client.ApiClient;
import com.ericsson.cifwk.taf.tools.cli.CLICommandHelper;
import com.ericsson.cifwk.taf.tools.cli.handlers.impl.RemoteObjectHandler;
import com.ericsson.oss.bsim.batch.data.model.MockWRANPicoBatch;
import com.ericsson.oss.bsim.data.model.BsimNodeData;
import com.ericsson.oss.bsim.data.model.NodeType;
import com.ericsson.oss.bsim.getters.api.BsimApiGetter;
import com.ericsson.oss.bsim.getters.api.BsimRemoteCommandExecutor;
import com.ericsson.oss.taf.hostconfigurator.HostGroup;
import com.ericsson.cifwk.taf.data.Host;
import com.ericsson.cifwk.taf.data.UserType;


public class BsimDeleteNodeApiOperator implements ApiOperator {

    private final Logger log = Logger.getLogger(BsimDeleteNodeApiOperator.class);

    private final ApiClient client = ClientHelper.getClient();

    final BsimRemoteCommandExecutor executor = BsimApiGetter.getRemoteCommandExecutor(BsimApiGetter.getHostMaster());

    //final RemoteObjectHandler masterServerFileHandler = BsimApiGetter.getMasterHostFileHandler();
	 final RemoteObjectHandler masterServerFileHandler = BsimApiGetter.getGatewayFileHandler();

    private String planThatIsDeleted;

    public void deleteCreatedNode(final NodeType nodeType, final String nodeFdn) {

        // this method will invoke the delete function provided by the bsim
        // product
        // it will delete the node in CS, the export file and the site (if it
        // created by add node operation)
        // however, it does not delete all the stuff generated by the add node
        // operation
        String respVal = null;
        final String numb = client.invoke("DeleteNodesExecutor", "addFdnsForNodesToBeDeleted", nodeFdn).getValue();
        log.info("no of nodes to delete :" + numb);
        respVal = client.invoke("DeleteNodesExecutor", "runDeleteNodes").getValue();
        log.info(String.format("Invoking %1$s: %2$s", "deleteCreatedNode", respVal));
    }

    public void deleteE2ENodeUsingARNE(final BsimNodeData nodeData, final String nodeFdn, final String nodeName, final String siteName) {

        String fdnForArne = null;
        if (nodeData.getNodeType() != NodeType.DG2 && nodeData.getNodeType() != NodeType.BasebandT) {
            fdnForArne = nodeFdn.replace("SubNetwork=ONRM_ROOT_MO_R", "SubNetwork=ONRM_ROOT_MO");
        } else {
            final String fdnForArnetemp = nodeFdn.replace("SubNetwork=ONRM_ROOT_MO_R", "SubNetwork=ONRM_ROOT_MO");
            fdnForArne = fdnForArnetemp.replace("MeContext", "ManagedElement");
        }
        final String siteForArne = "SubNetwork=ONRM_ROOT_MO,Site=" + siteName;
        File arneDeleteNodeFile = null;
        String remoteARNEFileName = null;
        try {
            log.info("Creating ARNE Delete template locally first...");
            final String templateContent = getARNEDeleteTemplateContent();
            if (templateContent == null) {
                log.error("Creating ARNE template locally failed");
                return;
            }
            // Location of local file is: "src/main/resources/arne_templates/DeleteNodeTemplate_ARNE.xml";
            final String arneDeleteNodeFileName = "delete_" + nodeName + ".xml";
            arneDeleteNodeFile = new File(arneDeleteNodeFileName);
            final String arneFileContent = templateContent.replace("%NodeFdn%", fdnForArne).replace("%SiteName%", siteForArne);
            log.info(arneFileContent);
            if (!writeContentToFile(arneDeleteNodeFile, arneFileContent)) {
                log.error("Writing content to new file failed");
                return;
            }
            final String remoteARNEFileDirectory = checkAndCreateRemoteFolderIfNotExists();
            transferFileToRemoteServer(arneDeleteNodeFile, remoteARNEFileDirectory);
            remoteARNEFileName = executeCommandToDeleteNodeUsingArne(arneDeleteNodeFileName, remoteARNEFileDirectory);
        } finally {
            cleanUpLocalAndRemoteFiles(arneDeleteNodeFile, remoteARNEFileName);
        }
    }

    public void deleteE2EPicoNodeUsingARNE(final String nodeFdn, final String nodeName) {

        final String fdnForArne = nodeFdn.replace("SubNetwork=ONRM_ROOT_MO_R", "SubNetwork=ONRM_ROOT_MO").replace("MeContext=", "ManagedElement=");
        File arneDeleteNodeFile = null;
        String remoteARNEFileName = null;
        try {
            log.info("Creating ARNE Delete template locally first...");
            final String templateContent = getARNEPICODeleteTemplateContent();
            if (templateContent == null) {
                log.error("Creating ARNE template locally failed");
                return;
            }
            // Location of local file is: "src/main/resources/arne_templates/DeleteNodeTemplate_ARNE.xml";
            final String arneDeleteNodeFileName = "delete_" + nodeName + ".xml";
            arneDeleteNodeFile = new File(arneDeleteNodeFileName);
            final String arneFileContent = templateContent.replace("%NodeFdn%", fdnForArne);
            log.info(arneFileContent);
            if (!writeContentToFile(arneDeleteNodeFile, arneFileContent)) {
                log.error("Writing content to new file failed");
                return;
            }
            final String remoteARNEFileDirectory = checkAndCreateRemoteFolderIfNotExists();
            transferFileToRemoteServer(arneDeleteNodeFile, remoteARNEFileDirectory);
            remoteARNEFileName = executeCommandToDeleteNodeUsingArne(arneDeleteNodeFileName, remoteARNEFileDirectory);
        } finally {
            cleanUpLocalAndRemoteFiles(arneDeleteNodeFile, remoteARNEFileName);
        }
    }

    private void cleanUpLocalAndRemoteFiles(final File arneDeleteNodeFile, final String remoteARNEFileName) {
        try {
            log.info("Tyde up temp ARNE files...");
            if (arneDeleteNodeFile != null) {
                arneDeleteNodeFile.delete();
            }
            if (remoteARNEFileName != null) {
                final String deleteARNEDir = String.format("rm -rf %1$s", remoteARNEFileName);
                executor.simpleExec(deleteARNEDir);
            }
        } catch (final Exception ex) {

            log.warn("Error occurs when cleaning up files. Error: " + ex.getMessage());
        }
    }

    private String checkAndCreateRemoteFolderIfNotExists() {
        final String remoteHomeDirctory = executor.simpleExec("pwd ");
        final String remoteARNEFileDirectory = remoteHomeDirctory + "/ERICTAFbsim_ARNE_FILES/";
        final String checkAndCreateDirCommand = String.format("[ ! -d %1$s ] && mkdir %1$s", remoteARNEFileDirectory);
        executor.simpleExec(checkAndCreateDirCommand);
        return remoteARNEFileDirectory;
    }

    private String executeCommandToDeleteNodeUsingArne(final String arneDeleteNodeFileName, final String remoteARNEFileDirectory) {
        String remoteARNEFileName;
        log.info("Deleting node using ARNE delete template...");
        remoteARNEFileName = remoteARNEFileDirectory + arneDeleteNodeFileName;
        final String deleteNodeCommand = String.format("/opt/ericsson/arne/bin/import.sh -F %1$s -import -i_nau", remoteARNEFileName);
        final String execResult = executor.simpleExec(deleteNodeCommand);
        log.info(execResult);
        return remoteARNEFileName;
    }

    private void transferFileToRemoteServer(final File arneDeleteNodeFile, final String remoteARNEFileDirectory) {
        log.info(String.format("Transfer local file %1$s to the directory %2$s in remote server...", arneDeleteNodeFile.getAbsolutePath(),
                remoteARNEFileDirectory));
        //masterServerFileHandler.copyLocalFileToRemote(arneDeleteNodeFile.getAbsolutePath(), remoteARNEFileDirectory);
	 // new Starts
        final Host host = HostGroup.getGateway();
        final CLICommandHelper cmdHelper = new CLICommandHelper(host, host.getUsers(UserType.ADMIN).get(0));
        cmdHelper.openShell();

        if (masterServerFileHandler.copyLocalFileToRemote(arneDeleteNodeFile.getAbsolutePath(), remoteARNEFileDirectory)) {
            cmdHelper.runInteractiveScript("scp " + remoteARNEFileDirectory + "/" + arneDeleteNodeFile + " nmsadm@ossmaster:"
                    + remoteARNEFileDirectory);
            cmdHelper.expect("Password:");
            cmdHelper.interactWithShell("nms27511");
        }
        log.info("File " + arneDeleteNodeFile + " transferred into the folder: " + remoteARNEFileDirectory);

        // new ends
    }

    public String readFile(final String filePath) {

        try (final Scanner sc = new Scanner(new File(filePath))) {
            return sc.useDelimiter("\\A").next();
        } catch (final FileNotFoundException e) {
            log.warn("The file does not exist.\r\nExpected File Path: " + filePath);
            return null;
        } catch (final NullPointerException npe) {
            log.warn("The file path is null");
            return null;
        } catch (final Exception ex) {
            log.error("Read file " + filePath + " failed. Error: " + ex.getMessage());
            return null;
        }
    }

    private String getARNEDeleteTemplateContent() {

        final String template = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
                + "<!DOCTYPE Model SYSTEM \"/opt/ericsson/arne/etc/arne12_2.dtd\">\n" + "<Model version=\"1\" importVersion=\"12.2\">\n" + "<Delete>\n"
                + "<Object fdn=\"%NodeFdn%\"/>\n" + "<Object fdn=\"%SiteName%\"/>\n" + "</Delete>\n" + "</Model>";
        return template;
    }

    private String getARNEPICODeleteTemplateContent() {

        final String template = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
                + "<!DOCTYPE Model SYSTEM \"/opt/ericsson/arne/etc/arne6_0.dtd\">\n" + "<Model version=\"1\" importVersion=\"6.0\">\n" + "<Delete>\n"
                + "<Object fdn=\"%NodeFdn%\"/>\n" + "</Delete>\n" + "</Model>";

        return template;
    }

    private boolean writeContentToFile(final File filePath, final String content) {

        if (!filePath.exists()) {
            try {
                filePath.createNewFile();
            } catch (final IOException e) {
                log.warn("Cannot create file: " + filePath);
                return false;
            }
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath, false))) {

            if (null != content && !"".equals(content)) {
                writer.println(content);
            } else {
                writer.print(content);
            }
            writer.flush();
        } catch (final IOException e) {
            log.warn("Failed to write content to file: " + filePath);
            return false;
        }

        return true;
    }

    public void deletePCA(final String pca) {

        if (planThatIsDeleted == null || !planThatIsDeleted.equalsIgnoreCase(pca)) {
            final String command = "/opt/ericsson/nms_cif_cs/etc/unsupported/bin/cstest -s Seg_masterservice_CS dp " + pca;
            final String outputStr = executor.simpleExec(command);

            if (outputStr.trim().length() == 0) {
                log.info("Delete plan configuration " + pca + " Successfully");
                planThatIsDeleted = pca;
            } else {
                log.warn("<font color=red>Delete plan configuration: Failed.\r\nError: " + outputStr + "</font>");
            }
        }
    }

    public void deletePCAwhenAuto(final MockWRANPicoBatch picoBatch) {
        final String autoPlanCommand = "/opt/ericsson/nms_cif_cs/etc/unsupported/bin/cstest -s Seg_masterservice_CS lp | grep -i" + picoBatch.getName();
        final String pcas = executor.simpleExec(autoPlanCommand);
        final String pcaArr[] = pcas.split(":");
        for (final String pca : pcaArr) {
            if (planThatIsDeleted == null || !planThatIsDeleted.equalsIgnoreCase(pca.trim())) {
                final String command = "/opt/ericsson/nms_cif_cs/etc/unsupported/bin/cstest -s Seg_masterservice_CS dp " + pca.trim();

                final String outputStr = executor.simpleExec(command);

                if (outputStr.trim().length() == 0) {
                    log.info("Delete plan configuration " + pca.trim() + " Successfully");
                    planThatIsDeleted = pca.trim();
                } else {
                    log.warn("<font color=red>Delete plan configuration: Failed.\r\nError: " + outputStr + "</font>");
                }
            }
        }
    }

    public void deleteAutoIntegrateFiles(final String nodeName) {

        // for SMRS files (SiteBasic, SiteEquipment,
        // AutoIntegrateSummaryFile.xml)
        final String cmdFormat1 = "find /var/opt/ericsson/smrsstore/ -name \"*%1$s*\" -exec rm -rf {} \\;";
        executor.simpleExec(String.format(cmdFormat1, nodeName));

        // for post final install files
        final String cmdFormat2 = "find /var/opt/ericsson/nms_umts_teng_bulkcm/data/postInstall/ -name \"*%1$s*\" -exec rm -rf {} \\;";
        executor.simpleExec(String.format(cmdFormat2, nodeName));

        // for site install files
        final String cmdFormat3 = "find /home/nmsadm/ -name \"*%1$s*\" -exec rm -rf {} \\;";
        executor.simpleExec(String.format(cmdFormat3, nodeName));

        // for Local files (SiteBasic, SiteEquipment,
        // AutoIntegrateSummaryFile.xml dor DG2)
        final String cmdFormat4 = "find /opt/ericsson/nms_umts_bsim_server/ -name \"*%1$s*\" -exec rm -rf {} \\;";
        executor.simpleExec(String.format(cmdFormat4, nodeName));

    }

    public void deleteBCGFiles(final String nodeName) {

        final String cmdFormat = "find /var/opt/ericsson/nms_umts_wran_bcg -name \"*%1$s*\" -exec rm -rf {} \\;";
        executor.simpleExec(String.format(cmdFormat, nodeName));
    }

    /**
     * Deletes Combined Configuration File from server.
     */
    public void deleteCCFFile() {

        executor.simpleExec("rm /home/nmsadm/noHardwareBindTAF_CCF.xml");
    }

    public void deleteSiteInstallationFile() {

        executor.Exec("rm /home/nmsadm/TAF_SiteInstallation.xml");
    }

    public void deleteLocalBasicnEquipmentnOSSFiles() {

        /*
         * executor.simpleExec("rm /tmp/SiteBasic_DG2.xml");
         * executor.simpleExec("rm /tmp/SiteEquipment_DG2.xml");
         */
	final BsimRemoteCommandExecutor executor2 = BsimApiGetter.getRemoteCommandExecutor(BsimApiGetter.getHostMasterRoot());
        executor2.simpleExec("rm /tmp/SiteBasic_DG2.xml");
        executor2.simpleExec("rm /tmp/SiteEquipment_DG2.xml");
        executor2.simpleExec("rm /tmp/OssNodeProtocol_DG2.xml");
    }

    public void deleteLocalBasicOSSFiles() {
        final BsimRemoteCommandExecutor executor2 = BsimApiGetter.getRemoteCommandExecutor(BsimApiGetter.getHostMasterRoot());
        executor2.simpleExec("rm -rf /tmp/SiteBasic_SPITFIRE.txt");
        executor2.simpleExec("rm -rf /tmp/SiteEquipment_SPITFIRE.txt");

    }
}

