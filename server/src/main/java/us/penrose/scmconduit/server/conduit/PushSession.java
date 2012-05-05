package us.penrose.scmconduit.server.conduit;

import static java.lang.System.out;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.apache.sshd.SshServer;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.FileSystemFactory;
import org.apache.sshd.server.FileSystemView;
import org.apache.sshd.server.PasswordAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.server.sftp.SftpSubsystem;

import us.penrose.scmconduit.core.bzr.Bzr;
import us.penrose.scmconduit.core.p4.P4Credentials;
import us.penrose.scmconduit.core.util.CommandRunner;
import us.penrose.scmconduit.server.ssh.SshFsView;

public class PushSession {
	public enum State {WAITING_FOR_INPUT, WORKING, FINISHED}
	
	private PushSession.State state = State.WAITING_FOR_INPUT;

	private final Integer pushId;
	private final SshDaemon sshServer;
	private final File onDisk;
	private final String hostname;
	
	private boolean hadErrors = false;
	private String explanation;
	
	private boolean isOpen = true;
	
	PushSession(Integer id, URI publicUri, File onDisk, CommandRunner shell) {
		this.pushId = id;
		this.onDisk = onDisk;
		this.hostname = publicUri.getHost();
		
		this.sshServer = new SshDaemon(onDisk, pushId);
		new Bzr(shell).createStackedBranch(publicUri.toString(), codePath().getAbsolutePath());
		
	}
	
	public boolean hadErrors(){
		return hadErrors;
	}
	
	public String explanation(){
		return explanation;
	}
	
	public Integer id() {
		return pushId;
	}
	
	public PushSession.State state() {
		return state;
	}
	
	private void markAsFinished(boolean hasErrors, String explanation){
		state = State.FINISHED;
		this.hadErrors = hasErrors;
		this.explanation = explanation;
	}
	
	public void inputReceived(final Pusher pusher){
		File codeLocation = codePath();
		state = PushSession.State.WORKING;
		out.println("Input received at " + codeLocation);
		
		if(sshServer.credentialsReceived.isEmpty()){
			close();
			markAsFinished(true, "No credentials received");
		}else if(sshServer.credentialsReceived.size()>1 && !allAreEqual(sshServer.credentialsReceived)){
			close();
			markAsFinished(true, "I received more than one set of credentials .. not sure how to proceed");
		} else {
			final P4Credentials credentials = sshServer.credentialsReceived.get(0);
			pusher.submitPush(codeLocation, credentials, new Pusher.PushListener() {
				public void pushSucceeded() {
					out.println("Push succeeded: " + explanation);
					close();
					markAsFinished(false, "IT WORKED");
				}
				public void nothingToPush() {
					out.println("There was nothing to push");
					close();
					markAsFinished(false, "There was nothing to push");
				}
				public void pushFailed(String explanation) {
					out.println("Push failed: " + explanation);
					close();
					markAsFinished(true, "THE PUSH FAILED: " + explanation);
				}
			});
		}
	}
	
	private boolean allAreEqual(List<?> objects) {
		Object previous = null;
		for (Object next : objects) {
			if(previous==null){
				previous = next;
			}else{
				if(!next.equals(previous)){
					return false;
				}
			}
		}
		return true;
	}

	private File codePath() {
		return new File(onDisk, "code");
	}

	synchronized void close(){
		try {
			sshServer.stop();
			isOpen = false;
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	public boolean isOpen() {
		return isOpen;
	}

	public String sftpUrl(){
		return "sftp://" + hostname + ":" + pushId + "/code";
	}
	
	public static class SshDaemon {
		private final List<P4Credentials> credentialsReceived = new ArrayList<P4Credentials>();
		private final SshServer sshd;
		
		public SshDaemon(final File path, int port) { 
			System.out.println("Serving " + path + " at port " + port);
			try {
				sshd = SshServer.setUpDefaultServer();
				
				
				sshd.setFileSystemFactory(new FileSystemFactory() {
					public FileSystemView createFileSystemView(String userName) {
						return new SshFsView("joe", false, path);
					}
				});
				
				sshd.setPort(port);
				sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider("hostkey.ser"));
				sshd.setPasswordAuthenticator(new PasswordAuthenticator() {
					
					public boolean authenticate(String username, String password, ServerSession session) {
						credentialsReceived.add(new P4Credentials(username, password));
						return true;
					}
				});
				
				sshd.setSubsystemFactories(new LinkedList<NamedFactory<Command>>());
				sshd.getSubsystemFactories().add(new SftpSubsystem.Factory());
				
				sshd.start();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			
		}

		public void stop() throws InterruptedException {
			sshd.stop();
		}
		
		
	}
	
}