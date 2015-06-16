package com.czbix.v2ex.ui.fragment;


import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.ShareActionProvider;
import android.text.Editable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.AbsListView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.czbix.v2ex.AppCtx;
import com.czbix.v2ex.R;
import com.czbix.v2ex.common.UserState;
import com.czbix.v2ex.common.exception.ConnectionException;
import com.czbix.v2ex.common.exception.RemoteException;
import com.czbix.v2ex.dao.DraftDao;
import com.czbix.v2ex.eventbus.CommentEvent;
import com.czbix.v2ex.helper.MultiList;
import com.czbix.v2ex.model.Comment;
import com.czbix.v2ex.model.IgnoreAble;
import com.czbix.v2ex.model.Node;
import com.czbix.v2ex.model.Postscript;
import com.czbix.v2ex.model.ThankAble;
import com.czbix.v2ex.model.Topic;
import com.czbix.v2ex.model.TopicWithComments;
import com.czbix.v2ex.model.db.Draft;
import com.czbix.v2ex.network.RequestHelper;
import com.czbix.v2ex.ui.MainActivity;
import com.czbix.v2ex.ui.TopicActivity;
import com.czbix.v2ex.ui.adapter.CommentAdapter;
import com.czbix.v2ex.ui.adapter.TopicAdapter;
import com.czbix.v2ex.ui.helper.ReplyFormHelper;
import com.czbix.v2ex.ui.loader.AsyncTaskLoader.LoaderResult;
import com.czbix.v2ex.ui.loader.TopicLoader;
import com.czbix.v2ex.ui.widget.HtmlMovementMethod;
import com.czbix.v2ex.util.ExceptionUtils;
import com.czbix.v2ex.util.ExecutorUtils;
import com.czbix.v2ex.util.LogUtils;
import com.czbix.v2ex.util.MiscUtils;
import com.czbix.v2ex.util.ViewUtils;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.Subscribe;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link TopicFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class TopicFragment extends Fragment implements SwipeRefreshLayout.OnRefreshListener,
        LoaderManager.LoaderCallbacks<LoaderResult<TopicWithComments>>,
        ReplyFormHelper.OnReplyListener, CommentAdapter.OnCommentActionListener, HtmlMovementMethod.OnHtmlActionListener, NodeListFragment.OnNodeActionListener, AbsListView.OnScrollListener {
    private static final String TAG = TopicFragment.class.getSimpleName();
    private static final String ARG_TOPIC = "topic";
    private static final int[] MENU_REQUIRED_LOGGED_IN = {R.id.action_ignore, R.id.action_reply, R.id.action_thank};

    private Topic mTopic;
    private SwipeRefreshLayout mLayout;
    private ListView mCommentsView;
    private TopicAdapter.ViewHolder mTopicHolder;
    private CommentAdapter mCommentAdapter;
    private LinearLayout mTopicView;
    private ReplyFormHelper mReplyForm;
    private String mCsrfToken;
    private String mOnceToken;
    private Draft mDraft;

    private MultiList<Comment> mComments;
    private int mCurPage;
    private int mMaxPage;
    private boolean mIsLoading;
    private boolean mLastIsFailed;

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @return A new instance of fragment TopicFragment.
     */
    public static TopicFragment newInstance(Topic topic) {
        TopicFragment fragment = new TopicFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_TOPIC, topic);
        fragment.setArguments(args);
        return fragment;
    }

    public TopicFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mTopic = getArguments().getParcelable(ARG_TOPIC);
        }

        mComments = new MultiList<>();
        mMaxPage = 1;
        mCurPage = 1;

        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        final View rootView = inflater.inflate(R.layout.fragment_topic, container, false);
        mLayout = ((SwipeRefreshLayout) rootView.findViewById(R.id.comments_layout));
        mLayout.setOnRefreshListener(this);

        mCommentsView = ((ListView) mLayout.findViewById(R.id.comments));

        mTopicView = (LinearLayout) inflater.inflate(R.layout.view_comment_topic, mCommentsView, false);
        mTopicView.setBackgroundColor(Color.WHITE);

        mTopicHolder = new TopicAdapter.ViewHolder(mTopicView.findViewById(R.id.topic));
        mTopicHolder.setContentListener(this);
        mTopicHolder.setNodeListener(this);
        mTopicHolder.fillData(mTopic);

        mCommentAdapter = new CommentAdapter(getActivity(), this);
        mCommentAdapter.setDataSource(mComments);
        mCommentsView.addHeaderView(mTopicView);
        mCommentsView.setAdapter(mCommentAdapter);
        mCommentsView.setOnScrollListener(this);

        return rootView;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final TopicActivity activity = (TopicActivity) getActivity();
        activity.setTitle(null);

        final ActionBar actionBar = activity.getSupportActionBar();
        Preconditions.checkNotNull(actionBar);
        actionBar.setDisplayHomeAsUpEnabled(true);

        setIsLoading(true);
        getLoaderManager().initLoader(0, null, this);
    }

    private void setIsLoading(boolean isLoading) {
        mIsLoading = isLoading;
        mLayout.setRefreshing(isLoading);
    }

    @Override
    public void onStart() {
        super.onStart();

        mDraft = null;
        final Draft draft = DraftDao.get(mTopic.getId());
        if (draft == null) {
            return;
        }

        if (draft.isExpired()) {
            DraftDao.delete(draft.mId);
            return;
        }

        mDraft = draft;
    }

    @Override
    public void onRefresh() {
        final TopicLoader loader = getLoader();
        if (loader == null) {
            return;
        }
        loader.forceLoad();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        if (!mTopic.hasInfo()) {
            return;
        }

        inflater.inflate(R.menu.menu_topic, menu);

        if (UserState.getInstance().isGuest()) {
            for (int i : MENU_REQUIRED_LOGGED_IN) {
                menu.findItem(i).setVisible(false);
            }
        }

        setupShareActionMenu(menu);

        super.onCreateOptionsMenu(menu, inflater);
    }

    private void setupShareActionMenu(Menu menu) {
        final ShareActionProvider actionProvider = ((ShareActionProvider)
                MenuItemCompat.getActionProvider(menu.findItem(R.id.action_share)));
        final Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, mTopic.getUrl());
        actionProvider.setShareIntent(shareIntent);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_copy_link:
                MiscUtils.setClipboard(getActivity(), getString(R.string.desc_topic_link),
                        mTopic.getUrl());
                return true;
            case R.id.action_refresh:
                setIsLoading(true);
                onRefresh();
                return true;
            case R.id.action_reply:
                toggleReplyForm();
                return true;
            case R.id.action_thank:
                onThank(mTopic);
                return true;
            case R.id.action_ignore:
                onIgnore(mTopic, true);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void toggleReplyForm() {
        if (mReplyForm != null) {
            mReplyForm.toggle();
            return;
        }

        final View rootView = getView();
        Preconditions.checkNotNull(rootView);
        final ViewStub viewStub = (ViewStub) rootView.findViewById(R.id.reply_form);
        mReplyForm = new ReplyFormHelper(viewStub, this);
    }

    @Override
    public Loader<LoaderResult<TopicWithComments>> onCreateLoader(int id, Bundle args) {
        LogUtils.d(TAG, "load topic, id: %d, title: %s", mTopic.getId(), mTopic.getTitle());
        return new TopicLoader(getActivity(), mTopic);
    }

    @Override
    public void onLoadFinished(Loader<LoaderResult<TopicWithComments>> loader, LoaderResult<TopicWithComments> result) {
        setIsLoading(false);
        if (result.hasException()) {
            mLastIsFailed = true;
            mCurPage = Math.max(mComments.listSize(), 1);
            if (ExceptionUtils.handleExceptionNoCatch(this, result.mException)) {
                getActivity().finish();
            }
            return;
        }
        mLastIsFailed = false;
        final TopicWithComments data = result.mResult;

        mTopic = data.mTopic;
        mTopicHolder.fillData(mTopic);
        mMaxPage = data.mMaxPage;
        final int oldSize = mComments.listSize();
        if (mCurPage > oldSize) {
            // new page
            mComments.addList(data.mComments);
        } else {
            mComments.setList(oldSize - 1, data.mComments);
        }

        mCommentAdapter.notifyDataSetChanged();

        fillPostscript(data.mPostscripts);
        getActivity().invalidateOptionsMenu();

        mCsrfToken = data.mCsrfToken;
        mOnceToken = data.mOnceToken;

        if (mDraft != null) {
            Preconditions.checkState(mReplyForm == null);

            toggleReplyForm();
            mReplyForm.setContent(mDraft.mContent);

            DraftDao.delete(mDraft.mId);
            mDraft = null;
        }
    }

    private void fillPostscript(List<Postscript> postscripts) {
        if (postscripts == null) {
            return;
        }

        final int childCount = mTopicView.getChildCount();
        if (childCount > 1) {
            mTopicView.removeViews(1, childCount - 1);
        }

        final LayoutInflater inflater = LayoutInflater.from(getActivity());

        for (int i = 0, size = postscripts.size(); i < size; i++) {
            Postscript postscript = postscripts.get(i);

            final View view = inflater.inflate(R.layout.view_postscript, mTopicView, false);
            ((TextView) view.findViewById(R.id.title)).setText(getString(R.string.title_postscript, i + 1));
            ((TextView) view.findViewById(R.id.time)).setText(postscript.mTime);
            final TextView contentView = (TextView) view.findViewById(R.id.content);
            ViewUtils.setHtmlIntoTextViewWithRes(contentView,
                    postscript.mContent, R.dimen.topic_picture_max_width);
            contentView.setMovementMethod(new HtmlMovementMethod(this));
            mTopicView.addView(view);
        }
    }

    @Override
    public void onLoaderReset(Loader<LoaderResult<TopicWithComments>> loader) {
        mCommentAdapter.setDataSource(null);
        mComments.clear();
        
        mCsrfToken = null;
        mOnceToken = null;
    }

    @Override
    public void onStop() {
        super.onStop();

        if (mReplyForm == null) {
            return;
        }

        // remember comment draft
        final Editable content = mReplyForm.getContent();
        if (TextUtils.isEmpty(content)) {
            return;
        }

        DraftDao.insert(mTopic.getId(), content.toString());

        Toast.makeText(getActivity(), R.string.toast_reply_saved_as_draft, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onReply(final CharSequence content) {
        AppCtx.getEventBus().register(this);
        final ScheduledFuture<?> future = ExecutorUtils.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    RequestHelper.reply(mTopic, content.toString(), mOnceToken);
                } catch (ConnectionException | RemoteException e) {
                    e.printStackTrace();
                    return;
                }

                AppCtx.getEventBus().post(new CommentEvent(CommentEvent.TYPE_REPLY));
            }
        }, 3, TimeUnit.SECONDS);

        mLayout.setRefreshing(true);
        Snackbar.make(mLayout, R.string.toast_sending, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_cancel, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (cancelRequest(future)) {
                            mReplyForm.setContent(content);
                        }
                    }
                }).show();

        mReplyForm.setVisibility(false);
    }

    @Subscribe
    public void onCommentRequestFinish(CommentEvent e) {
        AppCtx.getEventBus().unregister(this);
        if (e.mType == CommentEvent.TYPE_REPLY) {
            mReplyForm.setContent(null);
        } else if (e.mType == CommentEvent.TYPE_IGNORE_TOPIC) {
            Toast.makeText(getActivity(), R.string.toast_topic_ignored, Toast.LENGTH_LONG).show();
            getActivity().finish();
            return;
        }

        onRefresh();
    }

    @Override
    public void onCommentIgnore(final Comment comment) {
        onIgnore(comment, false);
    }

    private void onIgnore(final IgnoreAble obj, final boolean isTopic) {
        AppCtx.getEventBus().register(this);
        final ScheduledFuture<?> future = ExecutorUtils.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    RequestHelper.ignore(obj, mOnceToken);
                } catch (ConnectionException | RemoteException e) {
                    e.printStackTrace();
                    return;
                }

                AppCtx.getEventBus().post(new CommentEvent(isTopic ? CommentEvent.TYPE_IGNORE_TOPIC
                        : CommentEvent.TYPE_IGNORE_COMMENT));
            }
        }, 3, TimeUnit.SECONDS);

        showSendingMsg(future);
    }

    private void showSendingMsg(final ScheduledFuture<?> future) {
        mLayout.setRefreshing(true);
        Snackbar.make(mLayout, R.string.toast_sending, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_cancel, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        cancelRequest(future);
                    }
                }).show();
    }

    private boolean cancelRequest(Future<?> future) {
        if (future.cancel(false)) {
            AppCtx.getEventBus().unregister(this);
            mLayout.setRefreshing(false);
            return true;
        }

        Snackbar.make(mLayout, R.string.toast_cancel_failed, Snackbar.LENGTH_LONG).show();
        return false;
    }

    @Override
    public void onCommentThank(final Comment comment) {
        onThank(comment);
    }

    private void onThank(final ThankAble obj) {
        AppCtx.getEventBus().register(this);
        final ScheduledFuture<?> future = ExecutorUtils.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    RequestHelper.thank(obj, mCsrfToken);
                } catch (ConnectionException | RemoteException e) {
                    e.printStackTrace();
                    return;
                }

                AppCtx.getEventBus().post(new CommentEvent(CommentEvent.TYPE_THANK));
            }
        }, 3, TimeUnit.SECONDS);

        showSendingMsg(future);
    }

    @Override
    public void onCommentReply(Comment comment) {
        if (mReplyForm == null) {
            toggleReplyForm();
        } else {
            mReplyForm.setVisibility(true);
        }

        mReplyForm.getContent().append("@").append(comment.getMember().getUsername()).append(" ");
        mReplyForm.requestFocus();
    }

    @Override
    public void onCommentCopy(Comment comment) {
        final FragmentActivity context = getActivity();
        MiscUtils.setClipboard(context, null, comment.getContent());
    }

    @Override
    public void onCommentUrlClick(String url) {
        onUrlClick(url);
    }

    @Override
    public void onUrlClick(String url) {
        startActivity(MiscUtils.getUrlIntent(url));
    }

    @Override
    public void onImageClick(String source) {
        onUrlClick(source);
    }

    @Override
    public void onNodeOpen(Node node) {
        final Intent intent = new Intent(getActivity(), MainActivity.class);
        intent.putExtra(MainActivity.BUNDLE_NODE, node);
        startActivity(intent);
    }

    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) {

    }

    private TopicLoader getLoader() {
        return (TopicLoader) getLoaderManager().<LoaderResult<TopicWithComments>>getLoader(0);
    }

    @Override
    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        if (visibleItemCount <= 0 || mIsLoading || mLastIsFailed || (mCurPage >= mMaxPage)) {
            return;
        }

        final int lastVisibleItem = firstVisibleItem + visibleItemCount;
        if ((totalItemCount - lastVisibleItem) > 10) {
            return;
        }

        final TopicLoader loader = getLoader();

        setIsLoading(true);
        loader.setPage(++mCurPage);
        loader.startLoading();
    }
}
